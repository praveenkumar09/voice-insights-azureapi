package com.aia.voiceinsights.api.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptRepairServiceTest {

    @Test
    void acceptsAWordSwap() {
        assertTrue(TranscriptRepairService.plausible("And she is quite worried about tuē xiāo", "And she is quite worried about 退休"));
        assertTrue(TranscriptRepairService.plausible("她很担心退休", "他很担心退休"));
        assertTrue(TranscriptRepairService.plausible("His history is expecting their anak pertama", "His isteri is expecting their anak pertama"));
    }

    @Test
    void rejectsARewrite() {
        assertFalse(TranscriptRepairService.plausible("Budget is about three hundred dollars a month.", "The client has a modest monthly budget."));
        assertFalse(TranscriptRepairService.plausible("He works as a driver", "He works as a driver and has two children who are in primary school"));
    }

    @Test
    void keepsEnglishAndChineseApart() {
        assertEquals("worried about 退休 now", TranscriptRepairService.spaced("worried about tuē xiāo now", "worried about退休now"));
    }
}
