package com.aia.voiceinsights.api.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the policy system said about the customer's existing cover, at {@code fetchedAt}. Kept apart from the free-text
 * {@link CustomerProfile#getExistingPolicies()} (what was SAID): the two are reconciled by people, never merged silently.
 * The NRIC is never stored, only its masked form.
 *
 * {@code status}: FOUND | NONE | UNAVAILABLE. UNAVAILABLE means coverage is UNKNOWN, which is not the same as NONE.
 */
public record CoverageSnapshot(
        String status,
        String nricMasked,
        List<HeldPolicy> policies,
        Instant fetchedAt,
        boolean advisorConsented
) implements Serializable {

    public record HeldPolicy(String policyNumber, String productName, String category, String status,
                             String sumAssured, String insurer) implements Serializable {}

    public static String normalise(String productName) {
        return productName == null ? "" : productName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Normalised names of products held and IN FORCE: lapsed, surrendered or matured policies do not block a suggestion. */
    public Set<String> heldProductKeys() {
        if (policies == null) return Set.of();
        return policies.stream().filter(p -> "IN_FORCE".equals(p.status())).map(p -> normalise(p.productName()))
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    public boolean holds(String catalogProductName) {
        return heldProductKeys().contains(normalise(catalogProductName));
    }

    /** Names of the policies held and in force, as the policy system has them. */
    public List<String> inForceProductNames() {
        if (policies == null) return List.of();
        return policies.stream().filter(p -> "IN_FORCE".equals(p.status())).map(HeldPolicy::productName)
                .filter(n -> n != null && !n.isBlank()).distinct().toList();
    }

    /** One line for prompts and screens. Unchecked or unreachable is "not checked", never "none". */
    public static String describe(CoverageSnapshot c) {
        if (c == null || !c.verified()) return "Not checked";
        if (c.policies().isEmpty()) return "None (confirmed by the policy system)";
        return c.policies().stream().map(p -> p.productName() + " [" + p.category() + ", " + p.status() + "]")
                .collect(Collectors.joining("; "));
    }

    /** True when the policy system gave a real answer (FOUND or NONE), so cover is known rather than guessed. */
    public boolean verified() {
        return "FOUND".equals(status) || "NONE".equals(status);
    }
}
