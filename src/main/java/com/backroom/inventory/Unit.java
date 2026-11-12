package com.backroom.inventory;

public enum Unit {
    EACH(true), GRAM(false), KILOGRAM(false), MILLILITER(false), LITER(false);

    private final boolean whole;
    Unit(boolean whole) { this.whole = whole; }
    /** Count-based units accept whole quantities only; measured units allow up to three decimal places. */
    public boolean whole() { return whole; }
}
