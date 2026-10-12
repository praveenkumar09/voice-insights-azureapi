package com.aia.voiceinsights.api.util;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CalcTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    void readsNumbersWithSymbolsAndSuffixes() {
        assertEquals(java.util.List.of(d("6000"), d("8000")), Calc.numbersIn("S$6,000-8,000/month"));
        assertEquals(java.util.List.of(d("80000")), Calc.numbersIn("about 80k a year"));
        assertEquals(java.util.List.of(d("8000")), Calc.numbersIn("About $8,000 a month"));
        assertEquals(java.util.List.of(d("5")), Calc.numbersIn("5 months"));
    }

    @Test
    void readsNumberWords() {
        assertEquals(d("41"), Calc.wordsToNumber("forty-one").orElseThrow());
        assertEquals(d("8500"), Calc.wordsToNumber("eight thousand five hundred").orElseThrow());
        assertEquals(d("3"), Calc.wordsToNumber("three kids").orElseThrow());
        assertTrue(Calc.wordsToNumber("no numbers here").isEmpty());
    }

    @Test
    void parsesMoneyAndItsPeriod() {
        var m = Calc.parseMoney("About $8,000/month").orElseThrow();
        assertEquals(Calc.Period.MONTH, m.period());
        assertEquals(0, m.low().compareTo(d("8000")));
        var y = Calc.parseMoney("S$80k-100k a year").orElseThrow();
        assertEquals(Calc.Period.YEAR, y.period());
        assertTrue(y.isRange());
        assertEquals(Calc.Period.YEAR, Calc.parseMoney("$100,000").orElseThrow().period()); // a bare large figure is yearly
        assertTrue(Calc.parseMoney("not said").isEmpty());
    }

    @Test
    void monthlyMidpoint() {
        assertEquals(7000, Calc.monthlyMidpoint("S$6,000-8,000/month"));
        assertEquals(7500, Calc.monthlyMidpoint("S$80k-100k a year"));
        assertNull(Calc.monthlyMidpoint("none"));
    }

    @Test
    void halfOfIncome() {
        var t = Calc.scaleMoneyText("About $8,000/month", d("0.5")).orElseThrow();
        assertEquals("About $4,000/month", t.text());
        assertEquals("$8,000/month x 0.5 = $4,000/month", t.working());
    }

    @Test
    void dropOfThirtyPercent() {
        var f = Calc.factorForPercentChange(d("-30"));
        assertEquals(0, f.compareTo(d("0.7")));
        assertEquals("About $5,600/month", Calc.scaleMoneyText("About $8,000/month", f).orElseThrow().text());
    }

    @Test
    void rangesAndAnnualKeepTheirShape() {
        assertEquals("$3,000-4,000/month", Calc.scaleMoneyText("$6,000-8,000/month", d("0.5")).orElseThrow().text());
        assertEquals("About $50,000/year", Calc.scaleMoneyText("About $100,000/year", d("0.5")).orElseThrow().text());
    }

    @Test
    void countsMoveByTheDifferenceOnly() {
        assertEquals(0, Calc.totalAfterCountChange(3, 2, 3).value().compareTo(d("4")));
        assertEquals(0, Calc.totalAfterCountChange(3, 2, 4).value().compareTo(d("5")));
        assertEquals(0, Calc.totalAfterCountChange(3, 2, 2).value().compareTo(d("3"))); // already true: no change
        assertEquals(0, Calc.shift(41, 10).value().compareTo(d("51")));
    }

    @Test
    void sharesAndPercents() {
        assertEquals(d("5.0"), Calc.sharePercent(d("400"), d("8000")).value());
        assertEquals(0, Calc.percentOf(d("30"), d("8000")).value().compareTo(d("2400")));
        assertNull(Calc.sharePercent(d("1"), BigDecimal.ZERO).value());
    }

    @Test
    void premiumAgainstIncome() {
        var t = Calc.premiumShareOfIncome("$240-400/month", "About $8,000/month").orElseThrow();
        assertEquals("3-5%", t.text());
        assertEquals("5%", Calc.premiumShareOfIncome("$5,000 a year", "$100,000 a year").orElseThrow().text());
        assertTrue(Calc.premiumShareOfIncome("unknown", "$8,000/month").isEmpty());
    }

    @Test
    void canonicalFigures() {
        assertEquals(Calc.canonical("8,000"), Calc.canonical("8000.0"));
    }
}
