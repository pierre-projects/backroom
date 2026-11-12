package com.backroom.labels;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;

/**
 * Backroom QR tokens: 10 characters of Crockford base32 (50 random bits). No I, L, O or U, so a token read
 * aloud or typed from a damaged label is hard to get wrong. Shown to people as 7K4Q-2M9X-QA.
 */
final class Tokens {
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();
    private Tokens() {}

    static String generate() {
        while (true) {
            var token = new StringBuilder(LENGTH);
            for (int index = 0; index < LENGTH; index++) token.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            // At least one letter, so a typed token can never be mistaken for a barcode number.
            if (!token.chars().allMatch(Character::isDigit)) return token.toString();
        }
    }

    static String format(String token) {
        return token.substring(0, 4) + "-" + token.substring(4, 8) + "-" + token.substring(8);
    }

    /** Accepts lowercase, spaces and dashes, and the usual Crockford misreads (I and L as 1, O as 0). */
    static Optional<String> parse(String raw) {
        if (raw == null) return Optional.empty();
        var cleaned = raw.strip().toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "")
                .replace('I', '1').replace('L', '1').replace('O', '0');
        if (cleaned.length() != LENGTH) return Optional.empty();
        for (int index = 0; index < cleaned.length(); index++) {
            if (ALPHABET.indexOf(cleaned.charAt(index)) < 0) return Optional.empty();
        }
        return Optional.of(cleaned);
    }
}
