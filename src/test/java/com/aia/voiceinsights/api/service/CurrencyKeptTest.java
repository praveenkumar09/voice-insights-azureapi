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

    @Test
    void symbolsAndWordsBecomeTheSameCodeEverywhere() {
        assertEquals("Budget for protection is PHP 5,000 per month", Calc.withCurrencyCodes("Budget for protection is ₱5,000 per month"));
        assertEquals("has a PHP 2,000,000 mortgage", Calc.withCurrencyCodes("has a 2,000,000 pesos mortgage"));
        assertEquals("PHP 50,000/month", Calc.withCurrencyCodes("limampung libong 50,000 piso/month".replace("limampung libong ", "")));
        assertEquals("THB 8,000 per month", Calc.withCurrencyCodes("฿8,000 per month"));
        assertEquals("MYR 500 a month", Calc.withCurrencyCodes("RM500 a month"));
        assertEquals("IDR 1,500,000 a month", Calc.withCurrencyCodes("Rp1,500,000 a month"));
        assertEquals("VND 2,000,000 per month", Calc.withCurrencyCodes("2,000,000 ₫ per month"));
        assertEquals("HKD 3,000", Calc.withCurrencyCodes("HK$3,000"));
        assertEquals("a $500 budget and S$400", Calc.withCurrencyCodes("a $500 budget and S$400"), "dollars are left alone");
        assertEquals("RMB and the word format stay", Calc.withCurrencyCodes("RMB and the word format stay"));
        assertNull(Calc.withCurrencyCodes(null));
        assertEquals("PHP", Calc.currencyOf("5,000 piso"));
    }
}
