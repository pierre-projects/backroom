package com.backroom.labels;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Manufacturer barcode numbers (UPC-A, UPC-E, EAN-13, EAN-8, GTIN-14), normalized to GTIN-14 so equivalent
 * representations match: UPC-A 012345678905 and EAN-13 0012345678905 are the same product.
 * Leading zeros are significant and kept; values are strings, never numbers.
 */
final class Gtin {
    private Gtin() {}

    /**
     * Every GTIN-14 the input could mean. Usually one; an 8-digit number can be read as EAN-8 or UPC-E, so it may
     * produce two unless the scanner reported the format. Invalid input (wrong length or check digit) yields none.
     */
    static List<String> candidates(String raw, String formatHint) {
        var digits = raw == null ? "" : raw.strip().replace(" ", "").replace("-", "");
        var results = new ArrayList<String>();
        if (!digits.matches("[0-9]+")) return results;
        var hint = formatHint == null ? "" : formatHint.toLowerCase(Locale.ROOT);
        switch (digits.length()) {
            case 12, 13, 14 -> {
                if (validCheckDigit(digits)) results.add(pad(digits));
            }
            case 8 -> {
                if (!hint.equals("upc_e") && validCheckDigit(digits)) results.add(pad(digits));
                if (!hint.equals("ean_8")) {
                    var upcA = expandUpcE(digits);
                    if (upcA != null && validCheckDigit(upcA) && !results.contains(pad(upcA))) results.add(pad(upcA));
                }
            }
            default -> {}
        }
        return results;
    }

    /** Shortest conventional form for people: UPC-A (12) when it fits, otherwise EAN-13, otherwise GTIN-14. */
    static String display(String gtin14) {
        if (gtin14.startsWith("00")) return gtin14.substring(2);
        if (gtin14.startsWith("0")) return gtin14.substring(1);
        return gtin14;
    }

    static boolean validCheckDigit(String digits) {
        int sum = 0;
        for (int index = digits.length() - 2, position = 0; index >= 0; index--, position++) {
            int value = digits.charAt(index) - '0';
            sum += position % 2 == 0 ? value * 3 : value;
        }
        int check = (10 - sum % 10) % 10;
        return check == digits.charAt(digits.length() - 1) - '0';
    }

    private static String pad(String digits) {
        return "0".repeat(14 - digits.length()) + digits;
    }

    /** UPC-E (number system 0 or 1, six digits, check digit) to the equivalent 12-digit UPC-A, or null. */
    static String expandUpcE(String upcE) {
        if (upcE.length() != 8) return null;
        char system = upcE.charAt(0);
        if (system != '0' && system != '1') return null;
        var d = upcE.substring(1, 7);
        char check = upcE.charAt(7);
        char last = d.charAt(5);
        String body = switch (last) {
            case '0', '1', '2' -> d.substring(0, 2) + last + "0000" + d.substring(2, 5);
            case '3' -> d.substring(0, 3) + "00000" + d.substring(3, 5);
            case '4' -> d.substring(0, 4) + "00000" + d.charAt(4);
            default -> d.substring(0, 5) + "0000" + last;
        };
        return system + body + check;
    }
}
