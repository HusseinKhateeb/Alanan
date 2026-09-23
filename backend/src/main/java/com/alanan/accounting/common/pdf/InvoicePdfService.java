package com.alanan.accounting.common.pdf;

import java.awt.*;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class InvoicePdfService {
    private static final int W = 1190, H = 1684, M = 90;
    private static final Color BROWN = new Color(70, 34, 19);
    private static final Color GOLD = new Color(170, 109, 34);
    private static final Color CREAM = new Color(255, 249, 238);
    private static final Color MUTED = new Color(117, 91, 72);
    private final Font font;

    public InvoicePdfService() throws IOException, FontFormatException {
        try (var input = new ClassPathResource("fonts/Tajawal-Regular.ttf").getInputStream()) {
            font = Font.createFont(Font.TRUETYPE_FONT, input);
        }
    }

    public byte[] render(Map<String, Object> invoice, boolean sale) throws IOException {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) invoice.get("items");
        // Reserve space for totals and cancellation details on the final page.
        int pageCount = Math.max(1, (items.size() + 12) / 13);
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                var image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
                var g = image.createGraphics();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    paint(g, invoice, items, sale, pageIndex, pageCount);
                } finally { g.dispose(); }
                var page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                    stream.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private void paint(Graphics2D g, Map<String, Object> invoice, List<Map<String, Object>> items,
                       boolean sale, int pageIndex, int pageCount) {
        g.setColor(CREAM); g.fillRect(0, 0, W, H);
        g.setColor(BROWN); g.fillRect(0, 0, W, 19);
        g.setColor(GOLD); g.fillRect(M, 208, W - 2 * M, 3);
        left(g, "AL ANAN ROASTERY", M, 83, 38, BROWN);
        right(g, "محمص العنّان", W - M, 82, 38, BROWN);
        left(g, sale ? "SALES INVOICE" : "PURCHASE INVOICE", M, 150, 31, GOLD);
        right(g, sale ? "فاتورة مبيعات" : "فاتورة مشتريات", W - M, 150, 31, GOLD);
        left(g, value(invoice.get("invoice_number")), M, 267, 30, BROWN);
        right(g, value(invoice.get("invoice_date")), W - M, 267, 25, BROWN);
        left(g, sale ? "Customer / العميل" : "Supplier / المورد", M, 326, 23, MUTED);
        left(g, clip(g, value(invoice.get(sale ? "customer_name" : "supplier_name")), 650, 28), M, 365, 29, BROWN);
        if (!sale && invoice.get("supplier_reference") != null) {
            right(g, "Ref: " + clip(g, value(invoice.get("supplier_reference")), 300, 22), W - M, 365, 22, MUTED);
        }
        boolean cancelled = "CANCELLED".equals(invoice.get("status"));
        if (cancelled) {
            g.setColor(new Color(154, 45, 30)); g.fillRoundRect(M, 392, W - 2 * M, 57, 14, 14);
            center(g, "CANCELLED  /  ملغاة", W / 2, 432, 27, Color.WHITE);
        }
        int top = cancelled ? 486 : 442;
        g.setColor(BROWN); g.fillRoundRect(M, top, W - 2 * M, 52, 9, 9);
        left(g, "#", M + 18, top + 36, 21, Color.WHITE);
        left(g, "Item / الصنف", M + 66, top + 36, 21, Color.WHITE);
        right(g, "Qty / الكمية", 775, top + 36, 20, Color.WHITE);
        right(g, "Price / السعر", 938, top + 36, 20, Color.WHITE);
        right(g, "Total / المجموع", W - M - 16, top + 36, 20, Color.WHITE);
        int start = pageIndex * 13;
        int end = Math.min(items.size(), start + 13);
        for (int i = start; i < end; i++) {
            var row = items.get(i);
            int y = top + 60 + (i - start) * 52;
            if (i % 2 == 0) { g.setColor(new Color(246, 235, 218)); g.fillRect(M, y - 2, W - 2 * M, 49); }
            left(g, Integer.toString(i + 1), M + 18, y + 32, 20, MUTED);
            left(g, clip(g, value(row.get(sale ? "item_name" : "product_name")), 455, 21), M + 66, y + 32, 21, BROWN);
            right(g, value(row.get("quantity")), 775, y + 32, 20, BROWN);
            right(g, money(row.get(sale ? "unit_price" : "unit_cost")), 938, y + 32, 20, BROWN);
            right(g, money(row.get("line_total")), W - M - 16, y + 32, 20, BROWN);
        }
        if (pageIndex == pageCount - 1) {
            int y = Math.max(top + 100 + (end - start) * 52, 1390);
            g.setColor(GOLD); g.fillRect(M, y - 32, W - 2 * M, 2);
            right(g, "Total / الإجمالي: " + money(invoice.get("total")) + " ILS", W - M, y, 27, BROWN);
            right(g, "Paid / المدفوع: " + money(invoice.get("paid")) + " ILS", W - M, y + 42, 23, BROWN);
            right(g, "Due / المتبقي: " + money(invoice.get("outstanding")) + " ILS", W - M, y + 82, 23, BROWN);
            if (cancelled) {
                @SuppressWarnings("unchecked") var cancellation = (Map<String, Object>) invoice.get("cancellation");
                if (cancellation != null) left(g, "Cancellation / الإلغاء: " + clip(g, value(cancellation.get("reason")), 600, 20), M, y + 84, 20, new Color(154, 45, 30));
            }
        }
        g.setColor(GOLD); g.fillRect(M, H - 115, W - 2 * M, 2);
        left(g, "AL ANAN ROASTERY", M, H - 72, 18, MUTED);
        right(g, (pageIndex + 1) + " / " + pageCount, W - M, H - 72, 18, MUTED);
    }

    private String money(Object value) { return value == null ? "0.00" : new BigDecimal(value.toString()).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(); }
    private String value(Object value) { return value == null ? "" : value.toString(); }
    private String clip(Graphics2D g, String text, int maxWidth, int size) {
        g.setFont(font.deriveFont((float) size));
        while (!text.isEmpty() && g.getFontMetrics().stringWidth(text) > maxWidth) text = text.substring(0, text.length() - 1);
        return text;
    }
    private void left(Graphics2D g, String text, float x, float baseline, int size, Color color) { draw(g, text, x, baseline, size, color, 0); }
    private void right(Graphics2D g, String text, float x, float baseline, int size, Color color) { draw(g, text, x, baseline, size, color, 1); }
    private void center(Graphics2D g, String text, float x, float baseline, int size, Color color) { draw(g, text, x, baseline, size, color, 0.5f); }
    private void draw(Graphics2D g, String text, float x, float baseline, int size, Color color, float alignment) {
        g.setColor(color);
        g.setFont(font.deriveFont((float) size));
        var layout = new TextLayout(text.isEmpty() ? " " : text, g.getFont(), g.getFontRenderContext());
        layout.draw(g, x - layout.getAdvance() * alignment, baseline);
    }
}
