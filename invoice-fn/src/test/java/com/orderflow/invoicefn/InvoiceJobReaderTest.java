package com.orderflow.invoicefn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderflow.contracts.InvoiceJob;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvoiceJobReaderTest {

  private static final UUID ORDER = UUID.fromString("7f1c2c64-1a2b-4c3d-9e8f-0a1b2c3d4e5f");

  /** Forma exacta en que order-service (Jackson 3) escribe el record. */
  private static final String BODY =
      """
      {"orderId":"7f1c2c64-1a2b-4c3d-9e8f-0a1b2c3d4e5f","companyId":"ACME",
       "lines":[{"sku":"SKU-0001","quantity":2,"unitPrice":9.00},
                {"sku":"SKU-0002","quantity":3,"unitPrice":18.00}],
       "total":72.00,"currency":"BOB","confirmedAt":"2026-11-09T14:05:31.123456Z"}
      """;

  @Test
  void leeElJsonQueEscribeOrderService() {
    InvoiceJob job = InvoiceJobReader.read(BODY);

    assertThat(job)
        .isEqualTo(
            new InvoiceJob(
                ORDER,
                "ACME",
                List.of(
                    new InvoiceJob.Line("SKU-0001", 2, new BigDecimal("9.00")),
                    new InvoiceJob.Line("SKU-0002", 3, new BigDecimal("18.00"))),
                new BigDecimal("72.00"),
                "BOB",
                Instant.parse("2026-11-09T14:05:31.123456Z")));
  }

  @Test
  void aceptaLaFechaComoSegundosEpoch() {
    InvoiceJob job =
        InvoiceJobReader.read(
            BODY.replace("\"2026-11-09T14:05:31.123456Z\"", "1762697131.123456000"));

    assertThat(job.confirmedAt()).isEqualTo(Instant.parse("2025-11-09T14:05:31.123456Z"));
  }

  @Test
  void unBodyQueNoEsJsonSeRechaza() {
    assertThatThrownBy(() -> InvoiceJobReader.read("esto no es json"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unJsonIncompletoSeRechaza() {
    assertThatThrownBy(() -> InvoiceJobReader.read("{\"orderId\":\"" + ORDER + "\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lines");
  }

  @Test
  void sinLineasSeRechaza() {
    assertThatThrownBy(() -> InvoiceJobReader.read(BODY.replaceAll("(?s)\\[\\{.*}]", "[]")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
