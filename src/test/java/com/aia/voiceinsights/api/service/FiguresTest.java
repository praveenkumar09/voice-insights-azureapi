package com.aia.voiceinsights.api.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class FiguresTest {

    private static final Set<String> ALLOWED = Set.of("8000", "96000", "5", "3", "400");

    @Test
    void figuresFromTheSourcesAreFine() {
        assertTrue(JunoAskService.figuresAllowed("His income is about $8,000 a month, so $96,000 a year.", ALLOWED));
        assertTrue(JunoAskService.figuresAllowed("The premium range is about 3-5% of his income.", ALLOWED));
    }

    @Test
    void anInventedFigureIsFlagged() {
        assertFalse(JunoAskService.figuresAllowed("That would cost him about $450 a month.", ALLOWED));
        assertFalse(JunoAskService.figuresAllowed("It is roughly 12% of his income.", ALLOWED));
    }

    @Test
    void smallCountsAndYearsAreNotFlagged() {
        assertTrue(JunoAskService.figuresAllowed("He has 2 children and the plan is second of 3.", ALLOWED));
        assertTrue(JunoAskService.figuresAllowed("He retires in 2045.", ALLOWED));
        assertFalse(JunoAskService.figuresAllowed("He needs $13 more.", ALLOWED));
    }

    @Test
    void incomeIsKeptInOneStyle() {
        assertEquals("About $8,000/month", ProfileExtractionService.normaliseMoney("8k a month"));
        assertEquals("$6,000-8,000/month", ProfileExtractionService.normaliseMoney("S$6,000-8,000 per month"));
        assertEquals("About $100,000/year", ProfileExtractionService.normaliseMoney("around 100k per year"));
        assertEquals("not said", ProfileExtractionService.normaliseMoney("not said"));
    }
}
