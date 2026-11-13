package com.backroom.labels;

import static com.backroom.access.Role.MANAGER;
import static com.backroom.access.Role.OWNER;

import com.backroom.access.AccessPolicy;
import com.backroom.inventory.ItemQueries;
import com.backroom.locations.LocationQueries;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Printable labels, generated on demand and never stored. Each label carries the QR link, the resource name,
 * one line of detail and the human-readable code for typing when a camera can't read it.
 * Layout sizes match common sheets: A4 21-up 63.5 × 38.1 mm (L7160 size), US Letter 10-up 4 × 2 in (5163 size).
 */
@Service
public class LabelPdfService {
    static final int MAX_LABELS = 105;
    private static final float MM = 72f / 25.4f;
    private static final float INCH = 72f;

    /** Sheet geometry in PDF points; top and left are measured from the page's top-left corner. */
    record Sheet(
            PDRectangle page,
            int columns,
            int rows,
            float labelWidth,
            float labelHeight,
            float left,
            float top,
            float pitchX,
            float pitchY,
            String title) {
        int perPage() {
            return columns * rows;
        }
    }

    static Sheet sheet(LabelDtos.Layout layout) {
        return switch (layout) {
            case A4_21 ->
                new Sheet(
                        PDRectangle.A4,
                        3,
                        7,
                        63.5f * MM,
                        38.1f * MM,
                        7.21f * MM,
                        15.15f * MM,
                        66.04f * MM,
                        38.1f * MM,
                        "A4, 21 labels of 63.5 × 38.1 mm");
            case LETTER_10 ->
                new Sheet(
                        PDRectangle.LETTER,
                        2,
                        5,
                        4 * INCH,
                        2 * INCH,
                        0.15625f * INCH,
                        0.5f * INCH,
                        4.1875f * INCH,
                        2 * INCH,
                        "US Letter, 10 labels of 4 × 2 in");
            case SINGLE ->
                new Sheet(
                        new PDRectangle(63.5f * MM, 38.1f * MM),
                        1,
                        1,
                        63.5f * MM,
                        38.1f * MM,
                        0,
                        0,
                        63.5f * MM,
                        38.1f * MM,
                        "Single label, 63.5 × 38.1 mm");
        };
    }

    record Label(String link, String name, String detail, String code) {}

    private final CodeService codes;
    private final ItemQueries items;
    private final LocationQueries locations;
    private final AccessPolicy access;

    LabelPdfService(CodeService codes, ItemQueries items, LocationQueries locations, AccessPolicy access) {
        this.codes = codes;
        this.items = items;
        this.locations = locations;
        this.access = access;
    }

    /** Creates any missing QR codes (reusing existing ones) and returns the PDF. Owners and Managers only. */
    @Transactional
    public byte[] labels(UUID orgId, LabelDtos.LabelRequest request) {
        access.lock(orgId, OWNER, MANAGER);
        var labels = new ArrayList<Label>();
        for (var target : request.targets()) {
            var code = codes.ensureQrLocked(orgId, target);
            var view = codes.view(code);
            if (target.type() == LabelDtos.TargetType.ITEM) {
                var item = items.require(orgId, target.id());
                var unit = item.unit().name().charAt(0)
                        + item.unit().name().substring(1).toLowerCase(Locale.ROOT);
                labels.add(new Label(
                        view.url(),
                        item.name(),
                        (item.sku() == null ? "" : item.sku() + " · ") + unit,
                        view.display()));
            } else {
                var location = locations.require(orgId, target.id());
                labels.add(new Label(
                        view.url(),
                        location.name(),
                        location.parentName() == null ? "Location" : "in " + location.parentName(),
                        view.display()));
            }
        }
        var start = request.startPosition() == null ? 1 : request.startPosition();
        return render(
                sheet(request.layout()), Math.min(start, sheet(request.layout()).perPage()), labels);
    }

    @Transactional(readOnly = true)
    public byte[] calibration(UUID orgId, LabelDtos.Layout layout) {
        access.require(orgId, OWNER, MANAGER);
        return calibration(sheet(layout));
    }

    // ---------- Drawing ----------

    static byte[] render(Sheet sheet, int startPosition, List<Label> labels) {
        try (var document = new PDDocument();
                var out = new ByteArrayOutputStream()) {
            var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            var regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var mono = new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD);
            int slot = startPosition - 1;
            PDPageContentStream stream = null;
            try {
                for (var label : labels) {
                    if (stream == null || slot >= sheet.perPage()) {
                        if (stream != null) {
                            stream.close();
                            slot = 0;
                        }
                        var page = new PDPage(sheet.page());
                        document.addPage(page);
                        stream = new PDPageContentStream(document, page);
                    }
                    int column = slot % sheet.columns(), row = slot / sheet.columns();
                    float x = sheet.left() + column * sheet.pitchX();
                    float top = sheet.page().getHeight() - sheet.top() - row * sheet.pitchY();
                    drawLabel(stream, sheet, x, top - sheet.labelHeight(), label, bold, regular, mono);
                    slot++;
                }
            } finally {
                if (stream != null) stream.close();
            }
            document.getDocumentInformation().setTitle("Backroom labels");
            document.save(out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void drawLabel(
            PDPageContentStream stream,
            Sheet sheet,
            float x,
            float y,
            Label label,
            PDFont bold,
            PDFont regular,
            PDFont mono)
            throws IOException {
        float w = sheet.labelWidth(), h = sheet.labelHeight();
        float pad = Math.min(h * 0.09f, 3 * MM);
        float qr = Math.min(h - 2 * pad, w * 0.42f);
        drawQr(stream, label.link(), x + pad, y + (h - qr) / 2, qr);

        float textX = x + pad + qr + 2 * MM;
        float textWidth = x + w - pad - textX;
        float scale = h / (38.1f * MM); // 1.0 on A4 labels, larger on the taller Letter labels
        float nameSize = 9.5f * scale, detailSize = 7f * scale, codeSize = 8.5f * scale;

        // Name: up to two lines, shrinking slightly before truncating.
        var name = safe(label.name(), bold);
        List<String> lines = wrap(name, bold, nameSize, textWidth);
        while (lines.size() > 2 && nameSize > 7f * scale) {
            nameSize -= 0.5f;
            lines = wrap(name, bold, nameSize, textWidth);
        }
        if (lines.size() > 2)
            lines = List.of(
                    lines.get(0),
                    ellipsize(
                            lines.get(1) + " " + String.join(" ", lines.subList(2, lines.size())),
                            bold,
                            nameSize,
                            textWidth));
        float baseline = y + h - pad - nameSize;
        stream.setNonStrokingColor(0f, 0f, 0f);
        for (var line : lines) {
            text(stream, bold, nameSize, textX, baseline, line);
            baseline -= nameSize * 1.15f;
        }

        text(
                stream,
                regular,
                detailSize,
                textX,
                baseline - detailSize * 0.25f,
                ellipsize(safe(label.detail(), regular), regular, detailSize, textWidth));
        text(stream, mono, codeSize, textX, y + pad + 1, ellipsize(label.code(), mono, codeSize, textWidth));
    }

    /** QR with error correction M and a four-module quiet zone, drawn as vector squares so it prints crisply. */
    private static void drawQr(PDPageContentStream stream, String contents, float x, float y, float size)
            throws IOException {
        try {
            var matrix = new QRCodeWriter()
                    .encode(
                            contents,
                            BarcodeFormat.QR_CODE,
                            0,
                            0,
                            Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 0));
            int modules = matrix.getWidth() + 8;
            float cell = size / modules;
            stream.setNonStrokingColor(0f, 0f, 0f);
            for (int row = 0; row < matrix.getHeight(); row++) {
                for (int column = 0; column < matrix.getWidth(); column++) {
                    if (matrix.get(column, row))
                        stream.addRect(
                                x + (column + 4) * cell, y + size - (row + 5) * cell, cell + 0.01f, cell + 0.01f);
                }
            }
            stream.fill();
        } catch (WriterException failure) {
            throw new IllegalStateException("QR encoding failed", failure);
        }
    }

    static byte[] calibration(Sheet sheet) {
        try (var document = new PDDocument();
                var out = new ByteArrayOutputStream()) {
            var page = new PDPage(sheet.page());
            document.addPage(page);
            var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            var regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            float pageHeight = sheet.page().getHeight();
            try (var stream = new PDPageContentStream(document, page)) {
                stream.setStrokingColor(0.55f, 0.55f, 0.55f);
                stream.setLineWidth(0.5f);
                stream.setNonStrokingColor(0.45f, 0.45f, 0.45f);
                for (int slot = 0; slot < sheet.perPage(); slot++) {
                    int column = slot % sheet.columns(), row = slot / sheet.columns();
                    float x = sheet.left() + column * sheet.pitchX();
                    float y = pageHeight - sheet.top() - row * sheet.pitchY() - sheet.labelHeight();
                    stream.addRect(x, y, sheet.labelWidth(), sheet.labelHeight());
                    stream.stroke();
                    text(stream, regular, 9, x + 6, y + sheet.labelHeight() - 14, String.valueOf(slot + 1));
                }
                stream.setNonStrokingColor(0f, 0f, 0f);
                float headerY = pageHeight - Math.max(sheet.top() * 0.45f, 14);
                text(stream, bold, 9, 36, headerY, "Backroom label calibration — " + sheet.title());
                text(
                        stream,
                        regular,
                        8,
                        36,
                        headerY - 11,
                        "Print at 100% (Actual size) with Fit to page turned off, then hold this page against a label sheet.");

                // 50 mm ruler in the bottom margin.
                float rulerX = 36,
                        rulerY = Math.max(10, (pageHeight - sheet.top() - sheet.rows() * sheet.pitchY()) * 0.55f);
                stream.setStrokingColor(0f, 0f, 0f);
                stream.setLineWidth(0.8f);
                stream.moveTo(rulerX, rulerY);
                stream.lineTo(rulerX + 50 * MM, rulerY);
                stream.stroke();
                for (int mm = 0; mm <= 50; mm += 5) {
                    float tick = mm % 10 == 0 ? 5 : 3;
                    stream.moveTo(rulerX + mm * MM, rulerY);
                    stream.lineTo(rulerX + mm * MM, rulerY + tick);
                    stream.stroke();
                }
                text(
                        stream,
                        regular,
                        8,
                        rulerX + 50 * MM + 8,
                        rulerY,
                        "This bar must measure exactly 50 mm (1.97 in).");
            }
            document.getDocumentInformation().setTitle("Backroom label calibration");
            document.save(out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    // ---------- Text helpers ----------

    private static void text(PDPageContentStream stream, PDFont font, float size, float x, float y, String value)
            throws IOException {
        if (value.isEmpty()) return;
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, y);
        stream.showText(value);
        stream.endText();
    }

    private static float width(PDFont font, float size, String value) {
        try {
            return font.getStringWidth(value) / 1000f * size;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    static List<String> wrap(String value, PDFont font, float size, float maxWidth) {
        var lines = new ArrayList<String>();
        var current = new StringBuilder();
        for (var word : value.split("\\s+")) {
            if (word.isEmpty()) continue;
            var candidate = current.isEmpty() ? word : current + " " + word;
            if (width(font, size, candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (width(font, size, word) <= maxWidth) current.append(word);
            else lines.add(ellipsize(word, font, size, maxWidth));
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines.isEmpty() ? List.of("") : lines;
    }

    static String ellipsize(String value, PDFont font, float size, float maxWidth) {
        if (width(font, size, value) <= maxWidth) return value;
        var text = value;
        while (!text.isEmpty() && width(font, size, text + "…") > maxWidth) text = text.substring(0, text.length() - 1);
        return text.strip() + "…";
    }

    /** The standard PDF fonts cover Western European text; anything else prints as "?" instead of failing the whole sheet. */
    static String safe(String value, PDFont font) {
        var result = new StringBuilder();
        value.codePoints().forEach(point -> {
            var character = new String(Character.toChars(point));
            try {
                font.encode(character);
                result.append(character);
            } catch (IOException | IllegalArgumentException unsupported) {
                result.append('?');
            }
        });
        return result.toString();
    }
}
