package com.orderflow.invoicefn;

import com.orderflow.contracts.InvoiceJob;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Factura en PDF: empresa, pedido, líneas, total, moneda y fecha (skill aws-lambda). */
final class InvoicePdf {

  private static final float MARGIN = 50;
  private static final float LEADING = 16;
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

  private InvoicePdf() {}

  static byte[] render(InvoiceJob job) {
    try (var document = new PDDocument();
        var out = new ByteArrayOutputStream()) {
      var writer = new PageWriter(document);
      writer.line(writer.bold, 16, "Factura");
      writer.blank();
      writer.line(writer.regular, 11, "Empresa: " + job.companyId());
      writer.line(writer.regular, 11, "Pedido: " + job.orderId());
      writer.line(writer.regular, 11, "Fecha: " + DATE.format(job.confirmedAt()));
      writer.line(writer.regular, 11, "Moneda: " + job.currency());
      writer.blank();
      writer.line(writer.bold, 11, "SKU | Cantidad | Precio unitario | Subtotal");
      for (InvoiceJob.Line line : job.lines()) {
        BigDecimal subtotal =
            line.unitPrice()
                .multiply(BigDecimal.valueOf(line.quantity()))
                .setScale(2, RoundingMode.HALF_EVEN);
        writer.line(
            writer.regular,
            11,
            "%s | %d | %s | %s"
                .formatted(
                    line.sku(), line.quantity(), money(line.unitPrice()), money(subtotal)));
      }
      writer.blank();
      writer.line(writer.bold, 12, "Total: " + money(job.total()) + " " + job.currency());
      writer.close();
      document.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String money(BigDecimal amount) {
    return amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
  }

  /** Escribe línea a línea y abre otra página cuando no queda espacio. */
  private static final class PageWriter {

    final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final PDDocument document;
    private PDPageContentStream stream;
    private float y;

    PageWriter(PDDocument document) throws IOException {
      this.document = document;
      newPage();
    }

    void line(PDType1Font font, float size, String text) throws IOException {
      if (y < MARGIN) {
        newPage();
      }
      stream.beginText();
      stream.setFont(font, size);
      stream.newLineAtOffset(MARGIN, y);
      stream.showText(text);
      stream.endText();
      y -= LEADING;
    }

    void blank() {
      y -= LEADING;
    }

    void close() throws IOException {
      stream.close();
    }

    private void newPage() throws IOException {
      if (stream != null) {
        stream.close();
      }
      var page = new PDPage(PDRectangle.A4);
      document.addPage(page);
      stream = new PDPageContentStream(document, page);
      y = page.getMediaBox().getHeight() - MARGIN;
    }
  }
}
