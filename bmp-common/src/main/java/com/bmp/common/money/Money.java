package com.bmp.common.money;

import java.util.Objects;

/**
 * Money as integer paise. LOCKED DECISION — never floats, never BigDecimal for storage.
 *
 * <p>₹1 = 100 paise. All arithmetic is exact long arithmetic. Display formatting
 * happens only at the UI edge, never inside business logic.
 *
 * <p>Persist the raw {@code paise} long in the database (BIGINT). JPA embeddable
 * mapping is provided by {@link MoneyAttributeConverter} if you prefer a converter.
 */
public record Money(long paise) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public Money {
        // negative money is allowed only as a ledger debit; guard construction paths in callers
    }

    public static Money ofPaise(long paise) {
        return new Money(paise);
    }

    /** Convenience for literals in tests and seeds: Money.ofRupees(299) == ₹299.00 */
    public static Money ofRupees(long rupees) {
        return new Money(Math.multiplyExact(rupees, 100));
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(paise, other.paise));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(paise, other.paise));
    }

    /**
     * Commission-style percentage of this amount, in basis points to keep it exact.
     * 12% == 1200 bps. Rounds half-up to the nearest paisa — the ONE rounding rule
     * used everywhere so payment, payout and ledger always agree to the paisa.
     */
    public Money percentBps(int basisPoints) {
        long numerator = Math.multiplyExact(paise, basisPoints);
        long rounded = Math.floorDiv(numerator + 5_000, 10_000);
        return new Money(rounded);
    }

    public boolean isNegative()  { return paise < 0; }
    public boolean isZero()      { return paise == 0; }
    public boolean gte(Money o)  { return paise >= o.paise; }

    /** Display only. Never parse this back. */
    public String display() {
        long rupees = paise / 100;
        long p = Math.abs(paise % 100);
        return "₹" + String.format("%,d.%02d", rupees, p);
    }

    @Override
    public int compareTo(Money o) {
        return Long.compare(paise, o.paise);
    }

    @Override
    public String toString() {
        return display();
    }

    public static Money requireNonNegative(Money m, String field) {
        Objects.requireNonNull(m, field);
        if (m.isNegative()) throw new IllegalArgumentException(field + " must be >= 0, was " + m);
        return m;
    }
}
