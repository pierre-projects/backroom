package com.backroom.labels;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class LabelUnitTests {
    @Test
    void normalizesEquivalentBarcodesAndKeepsLeadingZeros() {
        assertThat(Gtin.candidates("036000291452", null)).containsExactly("00036000291452");
        assertThat(Gtin.candidates("0036000291452", null)).containsExactly("00036000291452");
        assertThat(Gtin.candidates(" 0 3600-0291452 ", null)).containsExactly("00036000291452");
        assertThat(Gtin.candidates("4006381333931", null)).containsExactly("04006381333931");
        assertThat(Gtin.display("00036000291452")).isEqualTo("036000291452");
        assertThat(Gtin.display("04006381333931")).isEqualTo("4006381333931");
    }

    @Test
    void rejectsBadCheckDigitsLengthsAndLetters() {
        assertThat(Gtin.candidates("036000291453", null)).isEmpty();
        assertThat(Gtin.candidates("12345", null)).isEmpty();
        assertThat(Gtin.candidates("03600029145A", null)).isEmpty();
    }

    @Test
    void expandsUpcEAndHonoursTheScannerFormat() {
        assertThat(Gtin.expandUpcE("04252614")).isEqualTo("042100005264");
        assertThat(Gtin.candidates("04252614", "upc_e")).containsExactly("00042100005264");
        assertThat(Gtin.candidates("96385074", "ean_8")).containsExactly("00000096385074");
        assertThat(Gtin.candidates("96385074", null)).contains("00000096385074");
    }

    @Test
    void tokensUseUnambiguousCharactersAndParseForgivingly() {
        for (int index = 0; index < 200; index++) {
            var token = Tokens.generate();
            assertThat(token).hasSize(10).matches("[0-9A-HJKMNP-TV-Z]{10}").matches(".*[A-Z].*");
            assertThat(Tokens.parse(Tokens.format(token).toLowerCase())).contains(token);
        }
        assertThat(Tokens.format("7K4Q2M9XQA")).isEqualTo("7K4Q-2M9X-QA");
        assertThat(Tokens.parse("7k4q 2m9x qa")).contains("7K4Q2M9XQA");
        assertThat(Tokens.parse("IL0O-2M9X-QA")).contains("11002M9XQA");
        assertThat(Tokens.parse("UUUU-UUUU-UU")).isEmpty();
        assertThat(Tokens.parse("short")).isEmpty();
    }

    @Test
    void recognizesOnlyOurOwnLinks() {
        var url = new PublicUrl("https://labels.example.test/");
        assertThat(url.link("7K4Q2M9XQA")).isEqualTo("https://labels.example.test/s/7K4Q2M9XQA");
        assertThat(url.tokenFrom("HTTPS://LABELS.EXAMPLE.TEST/s/7K4Q2M9XQA?x=1")).isEqualTo("7K4Q2M9XQA");
        assertThat(url.tokenFrom("https://evil.example/s/7K4Q2M9XQA")).isNull();
        assertThat(url.local()).isFalse();
        assertThat(new PublicUrl("http://localhost:8080").local()).isTrue();
    }

    @Test
    void printedQrDecodesBackToItsLink() throws Exception {
        var link = "https://labels.example.test/s/7K4Q2M9XQA";
        var pdf = LabelPdfService.render(LabelPdfService.sheet(LabelDtos.Layout.SINGLE), 1,
                List.of(new LabelPdfService.Label(link, "Espresso beans, 1 kg bag with a long name", "BEAN-01 · Kilogram", "7K4Q-2M9X-QA")));
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            var image = new PDFRenderer(document).renderImageWithDPI(0, 300);
            var pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
            var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels)));
            assertThat(new QRCodeReader().decode(bitmap).getText()).isEqualTo(link);
        }
    }

    @Test
    void sheetsPaginateFromTheStartPosition() throws Exception {
        var label = new LabelPdfService.Label("https://x.test/s/7K4Q2M9XQA", "Shelf A", "in Stockroom", "7K4Q-2M9X-QA");
        var sheet = LabelPdfService.sheet(LabelDtos.Layout.A4_21);
        try (var document = Loader.loadPDF(LabelPdfService.render(sheet, 21, List.of(label, label)))) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
        }
        try (var document = Loader.loadPDF(LabelPdfService.render(LabelPdfService.sheet(LabelDtos.Layout.LETTER_10), 1, java.util.Collections.nCopies(10, label)))) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
        try (var document = Loader.loadPDF(LabelPdfService.calibration(sheet))) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
    }
}
