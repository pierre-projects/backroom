package com.backroom.imports;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 reader, the counterpart to the exports CsvWriter. It accepts what spreadsheets save: an optional UTF-8 byte
 * order mark, CRLF or LF line ends, quoted fields with embedded commas, quotes ("" escapes) and newlines, and a comma or
 * semicolon delimiter (European Excel), detected from the header line. Each record keeps the 1-based line it starts on.
 */
final class CsvReader {
    record Record(int line, List<String> cells) {
        boolean blank() { return cells.stream().allMatch(String::isBlank); }
        String cell(int index) { return index >= 0 && index < cells.size() ? cells.get(index) : ""; }
    }

    private CsvReader() {}

    static List<Record> read(String text) {
        var source = text.startsWith("﻿") ? text.substring(1) : text;
        var delimiter = delimiter(source);
        var records = new ArrayList<Record>();
        var cells = new ArrayList<String>();
        var cell = new StringBuilder();
        var quoted = false;
        var line = 1;
        var recordLine = 1;
        for (int index = 0; index < source.length(); index++) {
            var ch = source.charAt(index);
            if (quoted) {
                if (ch == '"') {
                    if (index + 1 < source.length() && source.charAt(index + 1) == '"') { cell.append('"'); index++; }
                    else quoted = false;
                } else {
                    if (ch == '\n') line++;
                    cell.append(ch);
                }
            } else if (ch == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (ch == delimiter) {
                cells.add(cell.toString()); cell.setLength(0);
            } else if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && index + 1 < source.length() && source.charAt(index + 1) == '\n') index++;
                cells.add(cell.toString()); cell.setLength(0);
                records.add(new Record(recordLine, List.copyOf(cells)));
                cells.clear();
                line++;
                recordLine = line;
            } else {
                cell.append(ch);
            }
        }
        if (!cell.isEmpty() || !cells.isEmpty()) {
            cells.add(cell.toString());
            records.add(new Record(recordLine, List.copyOf(cells)));
        }
        // Trailing blank lines are common in spreadsheet exports; blank lines in the middle are kept so line numbers stay true.
        while (!records.isEmpty() && records.getLast().blank()) records.removeLast();
        return records;
    }

    /** Semicolon when the first line has semicolons outside quotes and no commas; otherwise comma. */
    static char delimiter(String text) {
        int commas = 0, semicolons = 0;
        var quoted = false;
        for (int index = 0; index < text.length(); index++) {
            var ch = text.charAt(index);
            if (ch == '"') quoted = !quoted;
            else if (!quoted && (ch == '\n' || ch == '\r')) break;
            else if (!quoted && ch == ',') commas++;
            else if (!quoted && ch == ';') semicolons++;
        }
        return semicolons > 0 && commas == 0 ? ';' : ',';
    }
}
