package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.util.Calc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CurrencyKeptTest {
    @Test
    void currencySaidIsTheCurrencyKept() {
        assertEquals("THB", Calc.currencyOf("90,000 baht a month"));
        assertEquals("THB", Calc.currencyOf("90,000 บาทต่อเดือน"));
        assertEquals("MYR", Calc.currencyOf("RM6,000 a month"));
        assertEquals("PHP", Calc.currencyOf("₱45,000 monthly"));
        assertEquals("VND", Calc.currencyOf("30,000,000 đồng"));
        assertNull(Calc.currencyOf("about $8,000 a month"));
        assertNull(Calc.currencyOf("S$80k a year"));
        assertNull(Calc.currencyOf(null));
    }

    @Test
    void incomeIsWrittenInThatCurrency() {
        assertEquals("About THB 90,000/month", ProfileExtractionService.normaliseMoney("90,000 THB per month"));
        assertEquals("THB 50,000-80,000/month", ProfileExtractionService.normaliseMoney("50,000 to 80,000 baht a month"));
        assertEquals("About $9,000/month", ProfileExtractionService.normaliseMoney("about 9,000 dollars a month"));
    }
}
