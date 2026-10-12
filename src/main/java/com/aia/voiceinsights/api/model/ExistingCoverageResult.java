package com.aia.voiceinsights.api.model;

import java.io.Serializable;
import java.util.List;

/** Existing Coverage Agent — "What does the customer already hold, so we do not suggest it again?" */
public record ExistingCoverageResult(
        String status,                     // FOUND | NONE | UNAVAILABLE | NOT_CHECKED
        List<String> heldProducts,         // catalogue product names held and in force: excluded from the shortlist
        List<String> heldCategories,       // kinds of cover held, for the other agents to reason about top-up vs new cover
        List<String> excludedFromShortlist,
        String note
) implements Serializable {
    /** Nothing excluded: used where no coverage was checked. */
    public static ExistingCoverageResult none() {
        return new ExistingCoverageResult("NOT_CHECKED", List.of(), List.of(), List.of(), "");
    }
}
