package com.backroom.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CsvReaderTests {
    @Test
    void readsQuotedFieldsEscapedQuotesAndEmbeddedNewlines() {
        var records = CsvReader.read(
                "﻿Item,SKU,Note\r\n\"Paper cups, 12 oz\",CUP-12,\"Say \"\"hi\"\"\"\r\n\"Two\nlines\",,end\r\n");
        assertThat(records).hasSize(3);
        assertThat(records.get(0).cells()).containsExactly("Item", "SKU", "Note");
        assertThat(records.get(1).cells()).containsExactly("Paper cups, 12 oz", "CUP-12", "Say \"hi\"");
        assertThat(records.get(1).line()).isEqualTo(2);
        assertThat(records.get(2).cells()).containsExactly("Two\nlines", "", "end");
        assertThat(records.get(2).line()).isEqualTo(3);
    }

    @Test
    void keepsSpreadsheetLineNumbersAcrossMultilineCellsAndBlankLines() {
        var records = CsvReader.read("Item\n\"a\nb\"\n\nc\n\n\n");
        assertThat(records).extracting(CsvReader.Record::line).containsExactly(1, 2, 4, 5);
        assertThat(records.get(2).blank()).isTrue();
        assertThat(records.get(3).cell(0)).isEqualTo("c");
    }

    @Test
    void detectsSemicolonsFromEuropeanExcel() {
        var records = CsvReader.read("Item;Unit;Starting quantity\nOat milk;L;\"2,5\"\n");
        assertThat(records.get(1).cells()).containsExactly("Oat milk", "L", "2,5");
        assertThat(CsvReader.delimiter("Item,Unit\n")).isEqualTo(',');
        assertThat(CsvReader.delimiter("\"a;b\",c\n")).isEqualTo(',');
    }

    @Test
    void toleratesRaggedRowsAndAMissingFinalNewline() {
        var records = CsvReader.read("Item,SKU,Unit\nBeans,COF-1\nMilk,OAT,L,extra");
        assertThat(records.get(1).cell(2)).isEmpty();
        assertThat(records.get(2).cells()).isEqualTo(List.of("Milk", "OAT", "L", "extra"));
    }
}
