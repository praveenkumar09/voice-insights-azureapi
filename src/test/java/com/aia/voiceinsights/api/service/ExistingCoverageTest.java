package com.aia.voiceinsights.api.service;

import static org.junit.jupiter.api.Assertions.*;

import com.aia.voiceinsights.api.model.*;
import java.net.Socket;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ExistingCoverageTest {

    private static final List<String> CATALOGUE = List.of("AIA Pro Achiever Invest", "AIA Secure Guard Term");

    private static CoverageSnapshot snap(String status, CoverageSnapshot.HeldPolicy... p) {
        return new CoverageSnapshot(status, "S****567D", List.of(p), Instant.now(), true);
    }

    private static CoverageSnapshot.HeldPolicy policy(String name, String category, String status) {
        return new CoverageSnapshot.HeldPolicy("P-1", name, category, status, null, "AIA");
    }

    private static ExistingCoverageService service() {
        return new ExistingCoverageService(null, "http://localhost:8085", "/mcp", 5);
    }

    private static CustomerProfile profileWith(CoverageSnapshot c) {
        CustomerProfile p = new CustomerProfile();
        p.setCoverage(c);
        return p;
    }

    @Test
    void onlyInForceProductsAreHeldAndNamesMatchLoosely() {
        var c = snap("FOUND", policy("aia secure-guard term", "LIFE_TERM", "IN_FORCE"),
                policy("AIA Pro Achiever Invest", "INVESTMENT_LINKED", "LAPSED"));
        assertTrue(c.holds("AIA Secure Guard Term"));
        assertFalse(c.holds("AIA Pro Achiever Invest"), "a lapsed policy must not block a suggestion");
    }

    @Test
    void analyseExcludesExactHeldProductOnly_andKeepsSameCategoryOthers() {
        var c = snap("FOUND", policy("AIA Secure Guard Term", "LIFE_TERM", "IN_FORCE"));
        var r = service().analyse(profileWith(c), CATALOGUE);
        assertEquals(List.of("AIA Secure Guard Term"), r.excludedFromShortlist());
        assertEquals(List.of("LIFE_TERM"), r.heldCategories());
    }

    @Test
    void noSnapshotIsNotChecked_andOutageIsUnavailable_neverNone() {
        assertEquals("NOT_CHECKED", service().analyse(profileWith(null), CATALOGUE).status());
        var r = service().analyse(profileWith(snap("UNAVAILABLE")), CATALOGUE);
        assertEquals("UNAVAILABLE", r.status());
        assertTrue(r.excludedFromShortlist().isEmpty());
    }

    @Test
    void shortlistNeverContainsAHeldProductEvenIfScoredHighest() {
        var scoring = new ProductScoringResult(List.of(
                new ProductScore("AIA Secure Guard Term", 95, List.of(), List.of()),
                new ProductScore("AIA Pro Achiever Invest", 60, List.of(), List.of())), "m");
        var coverage = service().analyse(profileWith(snap("FOUND", policy("AIA Secure Guard Term", "LIFE_TERM", "IN_FORCE"))), CATALOGUE);
        var agents = new RecommendationAgentService(null, null, null);

        var result = agents.shortlistProducts(scoring, coverage);
        assertEquals(List.of("AIA Pro Achiever Invest"), result.shortlistedProducts());
        assertTrue(result.rationale().contains("already holds"));
        assertEquals(List.of("AIA Secure Guard Term", "AIA Pro Achiever Invest"), agents.shortlistProducts(scoring).shortlistedProducts());
    }

    @Test
    void parseMapsToolResultAndTreatsGarbageAsUnavailable() {
        String json = "{\"status\":\"FOUND\",\"nricMasked\":\"S****567D\",\"policies\":[{\"policyNumber\":\"P-9\",\"productName\":\"X\","
                + "\"category\":\"LIFE_TERM\",\"status\":\"IN_FORCE\",\"sumAssured\":500000,\"insurer\":\"AIA\"}]}";
        var ok = service().parse(json, "S****567D", true);
        assertEquals("FOUND", ok.status());
        assertEquals("500000", ok.policies().get(0).sumAssured());
        assertEquals("UNAVAILABLE", service().parse("not json", "S****567D", true).status());
        assertEquals("UNAVAILABLE", service().parse("{\"status\":\"INVALID_ID\"}", "S****567D", true).status());
    }

    @Test
    void describeNeverTurnsUnknownIntoNone() {
        assertEquals("Not checked", CoverageSnapshot.describe(null));
        assertEquals("Not checked", CoverageSnapshot.describe(snap("UNAVAILABLE")));
        assertTrue(CoverageSnapshot.describe(snap("NONE")).startsWith("None"));
        assertEquals("AIA Secure Guard Term [LIFE_TERM, IN_FORCE]",
                CoverageSnapshot.describe(snap("FOUND", policy("AIA Secure Guard Term", "LIFE_TERM", "IN_FORCE"))));
    }

    @Test
    void nricValidation() {
        assertTrue(ExistingCoverageService.isValidNric("s1234567d"));
        assertTrue(ExistingCoverageService.isValidNric("S 1234567-D"));
        assertFalse(ExistingCoverageService.isValidNric("12345"));
        assertFalse(ExistingCoverageService.isValidNric(null));
    }

    /** Real MCP round trip; runs only when voice-insights-policy-mcp is up on :8085. */
    @Test
    void liveRoundTripAgainstPolicyMcpServer() throws Exception {
        try (Socket s = new Socket("localhost", 8085)) { /* reachable */ } catch (Exception e) {
            Assumptions.abort("policy MCP server not running on :8085");
        }
        var svc = service();
        var found = svc.callTool("S1234567D", true);
        assertEquals("FOUND", found.status());
        assertEquals(3, found.policies().size());
        assertEquals("S****567D", found.nricMasked());
        assertTrue(found.holds("AIA Secure Guard Term"));
        assertEquals("NONE", svc.callTool("T0123456A", true).status());
        assertEquals("UNAVAILABLE", svc.callTool("G1111111X", true).status());
    }
}
