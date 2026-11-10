package com.orderflow.invoicefn;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderflow.contracts.InvoiceJob;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class InvoicePdfTest {

  private static final UUID ORDER = UUID.randomUUID();

  private static InvoiceJob job(List<InvoiceJob.Line> lines, String total) {
    return new InvoiceJob(
        ORDER,
        "ACME",
        lines,
        new BigDecimal(total),
        "BOB",
        Instant.parse("2026-11-09T14:05:31Z"));
  }

  @Test
  void elResultadoEsUnPdfConEmpresaPedidoLineasTotalMonedaYFecha() throws Exception {
    byte[] pdf =
        InvoicePdf.render(
            job(
                List.of(
                    new InvoiceJob.Line("SKU-0001", 2, new BigDecimal("9.00")),
                    new InvoiceJob.Line("SKU-0002", 3, new BigDecimal("18.00"))),
                "72.00"));

    assertThat(new String(Arrays.copyOf(pdf, 4), StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    try (var document = Loader.loadPDF(pdf)) {
      String text = new PDFTextStripper().getText(document);
      assertThat(text)
          .contains(
              "Empresa: ACME",
              "Pedido: " + ORDER,
              "Fecha: 2026-11-09 14:05:31 UTC",
              "Moneda: BOB",
              "SKU-0001 | 2 | 9.00 | 18.00",
              "SKU-0002 | 3 | 18.00 | 54.00",
              "Total: 72.00 BOB");
    }
  }

  @Test
  void muchasLineasOcupanVariasPaginas() throws Exception {
    var lines = new ArrayList<InvoiceJob.Line>();
    for (int i = 1; i <= 100; i++) {
      lines.add(new InvoiceJob.Line("SKU-%04d".formatted(i), 1, new BigDecimal("1.00")));
    }

    byte[] pdf = InvoicePdf.render(job(lines, "100.00"));

    try (var document = Loader.loadPDF(pdf)) {
      assertThat(document.getNumberOfPages()).isGreaterThan(1);
      assertThat(new PDFTextStripper().getText(document)).contains("SKU-0100", "Total: 100.00");
    }
  }
}
