package com.aia.voiceinsights.api.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every sum Juno relies on is done here, never by the language model. The model reads facts and says <i>what</i> is wanted ("income
 * times one half", "one more dependant"); this class does the arithmetic with exact decimals and returns the working beside the
 * answer, so a figure can always be shown, checked and tested.
 */
public final class Calc {

    private Calc() {}

    public enum Period { MONTH, YEAR }

    /** A number with the working that produced it ("$8,000 x 0.5 = $4,000"). */
    public record Result(BigDecimal value, String working) {}

    /** An amount or a range ({@code low == high} for one figure) per month or per year. */
    public record Money(BigDecimal low, BigDecimal high, Period period) {
        public boolean isRange() { return low.compareTo(high) != 0; }

        public BigDecimal mid() { return low.add(high).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP); }

        public Money per(Period target) {
            if (target == period) return this;
            BigDecimal f = target == Period.MONTH ? BigDecimal.ONE.divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP) : BigDecimal.valueOf(12);
            return new Money(low.multiply(f), high.multiply(f), target);
        }
    }

    /** New income/budget text with the working behind it. */
    public record Text(String text, String working) {}

    // ── Reading numbers ──────────────────────────────────────────────────────────────────────────────────────────

    private static final Pattern NUM = Pattern.compile("(?<![\\p{L}\\d.])(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d+)?([kKmM](?![\\p{L}]))?");
    private static final Pattern YEARLY = Pattern.compile("(?i)\\b(year|yearly|annual|annually|annum|p\\.?a\\.?|per\\s+year|a\\s+year)\\b");
    private static final Pattern MONTHLY = Pattern.compile("(?i)\\b(month|monthly|mth|p\\.?m\\.?|per\\s+month|a\\s+month)\\b");

    /** The numbers in a text, in order: "S$6,000-8,000" -> 6000, 8000; "80k" -> 80000. Words like "eight thousand" are understood too. */
    public static List<BigDecimal> numbersIn(String text) {
        List<BigDecimal> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = NUM.matcher(text);
        while (m.find()) {
            BigDecimal v = new BigDecimal(m.group(1).replace(",", "") + (m.group(2) == null ? "" : m.group(2)));
            String suffix = m.group(3);
            if (suffix != null) v = v.multiply(suffix.equalsIgnoreCase("k") ? BigDecimal.valueOf(1_000) : BigDecimal.valueOf(1_000_000));
            out.add(v);
        }
        if (out.isEmpty()) wordsToNumber(text).ifPresent(out::add);
        return out;
    }

    private static final String[] ONES = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
            "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    /** "forty one" -> 41, "eight thousand five hundred" -> 8500, "two point five million" is not needed here. */
    public static Optional<BigDecimal> wordsToNumber(String text) {
        if (text == null) return Optional.empty();
        long total = 0, current = 0;
        boolean any = false;
        for (String raw : text.toLowerCase(Locale.ROOT).replace("-", " ").split("[^a-z]+")) {
            if (raw.isEmpty() || raw.equals("and")) continue;
            boolean matched = false;
            for (int i = 0; i < ONES.length; i++) if (ONES[i].equals(raw)) { current += i; matched = true; any = true; }
            for (int i = 2; i < TENS.length && !matched; i++) if (TENS[i].equals(raw)) { current += i * 10L; matched = true; any = true; }
            if (matched) continue;
            switch (raw) {
                case "hundred" -> { if (any) current = Math.max(current, 1) * 100; }
                case "thousand" -> { if (any) { total += Math.max(current, 1) * 1_000; current = 0; } }
                case "million" -> { if (any) { total += Math.max(current, 1) * 1_000_000; current = 0; } }
                default -> { /* an ordinary word */ }
            }
        }
        return any ? Optional.of(BigDecimal.valueOf(total + current)) : Optional.empty();
    }

    /** Income or a budget from free text ("About $8,000/month", "S$80k-100k a year", "6k to 8k monthly"). Empty when no figure is found. */
    public static Optional<Money> parseMoney(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        List<BigDecimal> nums = numbersIn(text);
        if (nums.isEmpty()) return Optional.empty();
        BigDecimal low = nums.stream().min(BigDecimal::compareTo).get();
        BigDecimal high = nums.stream().max(BigDecimal::compareTo).get();
        Period p;
        if (YEARLY.matcher(text).find()) p = Period.YEAR;
        else if (MONTHLY.matcher(text).find()) p = Period.MONTH;
        else p = high.compareTo(BigDecimal.valueOf(30_000)) >= 0 ? Period.YEAR : Period.MONTH; // a bare figure this large is a yearly one
        return Optional.of(new Money(low, high, p));
    }

    /** Monthly income as one whole number (the middle of a range), or null when nothing can be read. */
    public static Integer monthlyMidpoint(String text) {
        return parseMoney(text).map(m -> m.per(Period.MONTH).mid().setScale(0, RoundingMode.HALF_UP).intValue()).filter(v -> v > 0).orElse(null);
    }

    // ── Saying numbers ───────────────────────────────────────────────────────────────────────────────────────────

    /** "$8,000" (rounded to a sensible step: $10 once over $1,000). */
    public static String dollars(BigDecimal v) {
        BigDecimal r = v.abs().compareTo(BigDecimal.valueOf(1_000)) >= 0 ? v.divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP).multiply(BigDecimal.TEN) : v.setScale(0, RoundingMode.HALF_UP);
        return "$" + String.format(Locale.ROOT, "%,d", r.longValue());
    }

    public static String format(Money m) {
        String per = m.period() == Period.MONTH ? "/month" : "/year";
        return m.isRange() ? dollars(m.low()) + "-" + dollars(m.high()).substring(1) + per : "About " + dollars(m.low()) + per;
    }

    // ── The sums ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** An income/budget text multiplied by a factor: "half" is 0.5, "drops 30%" is 0.7, "doubles" is 2. Keeps the period of the original. */
    public static Optional<Text> scaleMoneyText(String text, BigDecimal factor) {
        if (factor == null || factor.signum() < 0) return Optional.empty();
        return parseMoney(text).map(m -> {
            Money scaled = new Money(m.low().multiply(factor), m.high().multiply(factor), m.period());
            String per = m.period() == Period.MONTH ? "/month" : "/year";
            String working = (m.isRange() ? dollars(m.low()) + "-" + dollars(m.high()).substring(1) : dollars(m.low())) + per + " x " + factor.stripTrailingZeros().toPlainString()
                    + " = " + (scaled.isRange() ? dollars(scaled.low()) + "-" + dollars(scaled.high()).substring(1) : dollars(scaled.low())) + per;
            return new Text(format(scaled), working);
        });
    }

    /** "drops 30%" -> 0.70; "rises 20%" -> 1.20. {@code percent} is signed (-30 for a drop). */
    public static BigDecimal factorForPercentChange(BigDecimal percent) {
        return BigDecimal.ONE.add(percent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
    }

    /** A whole-number base moved by a signed amount (age, dependants, counts). */
    public static Result shift(int base, int delta) {
        int out = base + delta;
        return new Result(BigDecimal.valueOf(out), base + (delta < 0 ? " - " : " + ") + Math.abs(delta) + " = " + out);
    }

    /** Result of changing a head-count from {@code now} to {@code after} when the total moves by the same amount. */
    public static Result totalAfterCountChange(int total, int now, int after) {
        int delta = after - now;
        Result r = shift(total, delta);
        return new Result(r.value(), r.working() + " (from " + now + " to " + after + ")");
    }

    /** {@code part} as a percentage of {@code whole}, to one decimal place. */
    public static Result sharePercent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) return new Result(null, "no whole to compare with");
        BigDecimal pct = part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
        return new Result(pct, dollars(part) + " / " + dollars(whole) + " = " + pct.stripTrailingZeros().toPlainString() + "%");
    }

    /** {@code pct}% of an amount. */
    public static Result percentOf(BigDecimal pct, BigDecimal amount) {
        BigDecimal v = amount.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new Result(v, pct.stripTrailingZeros().toPlainString() + "% of " + dollars(amount) + " = " + dollars(v));
    }

    /** A premium (range) as a share of income, both read from text and put on a monthly footing. */
    public static Optional<Text> premiumShareOfIncome(String premiumText, String incomeText) {
        Optional<Money> p = parseMoney(premiumText), i = parseMoney(incomeText);
        if (p.isEmpty() || i.isEmpty()) return Optional.empty();
        Money prem = p.get().per(Period.MONTH), inc = i.get().per(Period.MONTH);
        BigDecimal income = inc.mid();
        if (income.signum() <= 0) return Optional.empty();
        BigDecimal lo = sharePercent(prem.low(), income).value(), hi = sharePercent(prem.high(), income).value();
        String share = lo.compareTo(hi) == 0 ? lo.stripTrailingZeros().toPlainString() + "%" : lo.stripTrailingZeros().toPlainString() + "-" + hi.stripTrailingZeros().toPlainString() + "%";
        String working = (prem.isRange() ? dollars(prem.low()) + "-" + dollars(prem.high()).substring(1) : dollars(prem.low())) + "/month against about "
                + dollars(income) + "/month income = " + share;
        return Optional.of(new Text(share, working));
    }

    /** Numbers as they would be compared: "8,000" and "8000.0" are the same figure. */
    public static String canonical(String number) {
        try {
            return new BigDecimal(number.replace(",", "")).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return number;
        }
    }
}
