package com.alanan.accounting.common.pdf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class InvoicePdfServiceTest {
    @Test void rendersArabicAndMultipageInvoiceWithCancellation() throws Exception {
        var invoice = new HashMap<String, Object>();
        invoice.put("invoice_number", "SAL-123");
        invoice.put("invoice_date", "2026-09-23");
        invoice.put("customer_name", "عميل تجريبي");
        invoice.put("status", "CANCELLED");
        invoice.put("total", "140.00");
        invoice.put("paid", "0.00");
        invoice.put("outstanding", "0.00");
        invoice.put("cancellation", Map.of("reason", "خطأ في الفاتورة"));
        var items = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 14; i++) items.add(Map.of("item_name", "بهارات مشكلة", "quantity", "1", "unit_price", "10", "line_total", "10"));
        invoice.put("items", items);
        var bytes = new InvoicePdfService().render(invoice, true);
        assertThat(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(2);
            var image = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
            assertThat(image.getRGB(image.getWidth() / 2, 200)).isNotEqualTo(image.getRGB(0, 200));
        }
    }
}
