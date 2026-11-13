package com.backroom.exports;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * RFC 4180 CSV for spreadsheets: a UTF-8 byte order mark so Excel picks the right encoding, CRLF line ends, quoting only
 * where needed, and text cells that would start a formula (= + - @, tab, carriage return) prefixed with an apostrophe.
 * Numbers and dates are written as values, so they are never prefixed.
 */
final class CsvWriter {
    private final Writer out;
    private long rows;

    CsvWriter(Writer out) {
        this.out = out;
    }

    void bom() {
        write("﻿");
    }

    /** Writes one record. Strings are guarded text; BigDecimal, numbers, booleans and instants are plain values. */
    void row(List<?> cells) {
        var line = new StringBuilder();
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) line.append(',');
            line.append(cell(cells.get(index)));
        }
        line.append("\r\n");
        write(line.toString());
        rows++;
    }

    long rows() {
        return rows;
    }

    static String cell(Object value) {
        if (value == null) return "";
        if (value instanceof BigDecimal number)
            return number.stripTrailingZeros().toPlainString();
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Instant instant)
            return instant.truncatedTo(ChronoUnit.SECONDS).toString();
        return quote(guard(value.toString()));
    }

    static String guard(String text) {
        if (text.isEmpty()) return text;
        var first = text.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + text
                : text;
    }

    static String quote(String text) {
        var needs = text.indexOf(',') >= 0
                || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0
                || (!text.isEmpty() && (text.charAt(0) == ' ' || text.charAt(text.length() - 1) == ' '));
        return needs ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }

    private void write(String text) {
        try {
            out.write(text);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
