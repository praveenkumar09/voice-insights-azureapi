package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.CoverageSnapshot;
import com.aia.voiceinsights.api.model.CoverageSnapshot.HeldPolicy;
import com.aia.voiceinsights.api.model.CustomerProfile;
import com.aia.voiceinsights.api.model.ExistingCoverageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Asks the policy MCP server which policies a customer already holds, so no product they own is suggested again.
 *
 * The advisor TYPES the NRIC (it is stripped from every transcript and never reaches a prompt). It is used for this one
 * call and not kept: only the masked form is stored, in a {@link CoverageSnapshot} on the profile. A failed lookup is
 * UNAVAILABLE ("unknown"), never NONE ("holds nothing").
 */
@Service
public class ExistingCoverageService {

    private static final Logger log = LoggerFactory.getLogger(ExistingCoverageService.class);
    private static final String TOOL = "get_existing_policies";
    private static final Pattern NRIC = Pattern.compile("^[STFGM]\\d{7}[A-Z]$");

    private final CustomerProfileStore profileStore;
    private final String url;
    private final String endpoint;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public ExistingCoverageService(CustomerProfileStore profileStore,
                                   @Value("${voice.policy-mcp.url}") String url,
                                   @Value("${voice.policy-mcp.endpoint:/mcp}") String endpoint,
                                   @Value("${voice.policy-mcp.timeout-seconds:5}") int timeoutSeconds) {
        this.profileStore = profileStore;
        this.url = url;
        this.endpoint = endpoint;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public static boolean isValidNric(String raw) {
        return raw != null && NRIC.matcher(normalise(raw)).matches();
    }

    private static String normalise(String raw) {
        return raw == null ? "" : raw.replaceAll("[\\s-]", "").toUpperCase();
    }

    /** Looks the customer up, stores the snapshot on their profile and returns it. The NRIC is not retained. */
    public CoverageSnapshot lookUp(String profileId, String nric, boolean advisorConsented) {
        if (!advisorConsented) throw new IllegalArgumentException("The advisor must confirm the customer's consent first");
        if (!isValidNric(nric)) throw new IllegalArgumentException("That NRIC/FIN is not in a valid format");
        CustomerProfile profile = profileStore.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("No customer profile found for id " + profileId));

        CoverageSnapshot snapshot = callTool(normalise(nric), advisorConsented);
        profile.setCoverage(snapshot);
        profileStore.save(profile);
        return snapshot;
    }

    CoverageSnapshot callTool(String nric, boolean consented) {
        String masked = nric.charAt(0) + "****" + nric.substring(5);
        try (McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder(url).endpoint(endpoint).build())
                .requestTimeout(timeout).initializationTimeout(timeout).build()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(TOOL, Map.of("nric", nric)));
            if (Boolean.TRUE.equals(result.isError())) return unavailable(masked, consented, "tool error");
            for (McpSchema.Content c : result.content()) {
                if (c instanceof McpSchema.TextContent t) return parse(t.text(), masked, consented);
            }
            return unavailable(masked, consented, "empty tool result");
        } catch (Exception e) {
            return unavailable(masked, consented, e.getClass().getSimpleName());
        }
    }

    CoverageSnapshot parse(String json, String masked, boolean consented) {
        try {
            JsonNode root = mapper.readTree(json);
            String status = root.path("status").asText("");
            if (!Set.of("FOUND", "NONE").contains(status)) return unavailable(masked, consented, "status " + status);
            List<HeldPolicy> policies = new ArrayList<>();
            for (JsonNode p : root.path("policies")) {
                policies.add(new HeldPolicy(p.path("policyNumber").asText(null), p.path("productName").asText(null),
                        p.path("category").asText(null), p.path("status").asText(null),
                        p.hasNonNull("sumAssured") ? p.get("sumAssured").asText() : null, p.path("insurer").asText(null)));
            }
            log.info("Existing cover for {}: {} ({} policies)", masked, status, policies.size());
            return new CoverageSnapshot(status, masked, policies, Instant.now(), consented);
        } catch (Exception e) {
            return unavailable(masked, consented, "unreadable result");
        }
    }

    private CoverageSnapshot unavailable(String masked, boolean consented, String why) {
        log.warn("Existing-policies lookup for {} unavailable: {}", masked, why);
        return new CoverageSnapshot("UNAVAILABLE", masked, List.of(), Instant.now(), consented);
    }

    // ── The graph node's work ───────────────────────────────────────────────

    /**
     * Packages the stored snapshot for the pipeline: which catalogue products are excluded, which kinds of cover are held.
     * No snapshot (no NRIC was entered) is NOT_CHECKED: the pipeline then relies on what was said, as it did before.
     */
    public ExistingCoverageResult analyse(CustomerProfile profile, List<String> catalogue) {
        CoverageSnapshot c = profile.getCoverage();
        if (c == null) return new ExistingCoverageResult("NOT_CHECKED", List.of(), List.of(), List.of(),
                "No NRIC was entered, so existing cover was not checked against the policy system.");
        if ("UNAVAILABLE".equals(c.status())) return new ExistingCoverageResult("UNAVAILABLE", List.of(), List.of(), List.of(),
                "The policy system could not be reached, so existing cover is unknown. Using only what was said.");

        Set<String> held = new LinkedHashSet<>();
        Set<String> categories = new LinkedHashSet<>();
        for (HeldPolicy p : c.policies()) {
            if (!"IN_FORCE".equals(p.status())) continue;
            if (p.productName() != null) held.add(p.productName());
            if (p.category() != null) categories.add(p.category());
        }
        List<String> excluded = catalogue.stream().filter(c::holds).toList();
        String note = c.policies().isEmpty() ? "The policy system confirms the customer holds no policies."
                : held.size() + " policy(ies) in force; " + excluded.size() + " of them are in our catalogue and will not be suggested.";
        return new ExistingCoverageResult(c.status(), List.copyOf(held), List.copyOf(categories), excluded, note);
    }
}
