package com.backroom.exports;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTests {
    @Test
    void quotesOnlyWhatNeedsQuotingAndEndsLinesWithCrLf() {
        var out = new StringWriter();
        var csv = new CsvWriter(out);
        csv.bom();
        csv.row(List.of("Item", "Note"));
        csv.row(List.of("Paper cups, 12 oz", "He said \"enough\"\nfor now"));
        csv.row(Arrays.asList(" padded", null));
        assertThat(out.toString()).isEqualTo("﻿Item,Note\r\n\"Paper cups, 12 oz\",\"He said \"\"enough\"\"\nfor now\"\r\n\" padded\",\r\n");
        assertThat(csv.rows()).isEqualTo(3);
    }

    @Test
    void guardsTextThatSpreadsheetsWouldRunAsAFormula() {
        assertThat(CsvWriter.cell("=HYPERLINK(\"http://x\")")).isEqualTo("\"'=HYPERLINK(\"\"http://x\"\")\"");
        assertThat(CsvWriter.cell("+1")).isEqualTo("'+1");
        assertThat(CsvWriter.cell("-2")).isEqualTo("'-2");
        assertThat(CsvWriter.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(CsvWriter.cell("\tcmd")).isEqualTo("'\tcmd");
        assertThat(CsvWriter.cell("Oat milk")).isEqualTo("Oat milk");
    }

    @Test
    void writesNumbersAndDatesAsPlainValues() {
        assertThat(CsvWriter.cell(new BigDecimal("-2.500"))).isEqualTo("-2.5");
        assertThat(CsvWriter.cell(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(CsvWriter.cell(Instant.parse("2026-11-12T07:06:51.123456Z"))).isEqualTo("2026-11-12T07:06:51Z");
        assertThat(CsvWriter.cell(null)).isEmpty();
    }
}
