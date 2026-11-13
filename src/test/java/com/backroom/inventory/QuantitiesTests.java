package com.backroom.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.backroom.shared.web.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class QuantitiesTests {
    @Test
    void wholeUnitsRejectFractions() {
        assertThat(Quantities.positive(Unit.EACH, new BigDecimal("3"))).isEqualByComparingTo("3");
        assertThat(Quantities.positive(Unit.EACH, new BigDecimal("3.000"))).isEqualByComparingTo("3");
        assertThatThrownBy(() -> Quantities.positive(Unit.EACH, new BigDecimal("1.5")))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo("INVALID_QUANTITY");
    }

    @Test
    void measuredUnitsAllowThreeDecimalPlaces() {
        assertThat(Quantities.positive(Unit.KILOGRAM, new BigDecimal("0.125")).scale())
                .isEqualTo(3);
        assertThatThrownBy(() -> Quantities.positive(Unit.LITER, new BigDecimal("0.0001")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsZeroNegativeMissingAndOversizedValues() {
        assertThatThrownBy(() -> Quantities.positive(Unit.GRAM, BigDecimal.ZERO))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Quantities.positive(Unit.GRAM, new BigDecimal("-1")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Quantities.positive(Unit.GRAM, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Quantities.positive(Unit.GRAM, new BigDecimal("1000000000000")))
                .isInstanceOf(ApiException.class);
        assertThat(Quantities.nonNegative(Unit.GRAM, BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    void formatsWithoutTrailingZerosOrExponents() {
        assertThat(Quantities.text(new BigDecimal("10.000"))).isEqualTo("10");
        assertThat(Quantities.text(new BigDecimal("2.500"))).isEqualTo("2.5");
        assertThat(Quantities.text(new BigDecimal("0.000"))).isEqualTo("0");
    }

    @Test
    void fingerprintsIgnoreFormattingButNotContent() {
        var key = java.util.UUID.randomUUID();
        var item = java.util.UUID.randomUUID();
        var location = java.util.UUID.randomUUID();
        var a = new StockDtos.Receipt(key, item, location, new BigDecimal("2.50"), " INV-1 ", null);
        var b = new StockDtos.Receipt(key, item, location, new BigDecimal("2.5"), "INV-1", "");
        var c = new StockDtos.Receipt(key, item, location, new BigDecimal("2.6"), "INV-1", null);
        assertThat(StockService.hash(a.fingerprint()))
                .isEqualTo(StockService.hash(b.fingerprint()))
                .isNotEqualTo(StockService.hash(c.fingerprint()));
    }
}
