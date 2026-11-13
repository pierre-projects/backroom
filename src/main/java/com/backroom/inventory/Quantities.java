package com.backroom.inventory;

import com.backroom.shared.error.InvalidRequestException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact quantity rules shared by every stock command. Values are stored as numeric(15,3). */
public final class Quantities {
    static final int SCALE = 3;
    static final BigDecimal MAX = new BigDecimal("999999999999.999");

    private Quantities() {}

    /** A quantity that changes stock: greater than zero. */
    public static BigDecimal positive(Unit unit, BigDecimal value) {
        var normalized = nonNegative(unit, value);
        if (normalized.signum() == 0) throw invalid("Enter a quantity greater than zero.");
        return normalized;
    }

    /** A quantity that may be zero, such as a physical count of an empty shelf. */
    public static BigDecimal nonNegative(Unit unit, BigDecimal value) {
        if (value == null) throw invalid("Enter a quantity.");
        if (value.signum() < 0) throw invalid("Quantities cannot be negative.");
        var stripped = value.stripTrailingZeros();
        if (unit.whole() && stripped.scale() > 0) throw invalid("Each is counted in whole numbers.");
        if (stripped.scale() > SCALE) throw invalid("Use at most three decimal places.");
        if (value.compareTo(MAX) > 0) throw invalid("That quantity is too large.");
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /** Plain text for API views and audit metadata, without trailing zeros or exponent notation. */
    public static String text(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static InvalidRequestException invalid(String message) {
        return new InvalidRequestException("INVALID_QUANTITY", message);
    }
}
