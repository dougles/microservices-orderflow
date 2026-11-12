package com.orderflow.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.e2e.support.Api;
import com.orderflow.e2e.support.Aws;
import com.orderflow.e2e.support.Db;
import com.orderflow.e2e.support.E2EBase;
import com.orderflow.e2e.support.Tokens;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;

/**
 * F15 — Subir a {@code incoming/f15-<uuid>.csv}: 3 filas válidas con SKUs nuevos, 1 con basePrice
 * {@code -1}, 1 que repite un SKU del mismo archivo. Los 3 SKUs nuevos aparecen en {@code GET
 * /api/catalog/products}; existe {@code errors/f15-<uuid>.csv.errors.csv} con exactamente 2 filas:
 * INVALID_PRICE y DUPLICATE_SKU_IN_FILE con sus números de línea.
 */
class F15CsvWithInvalidRowsE2E extends E2EBase {

  private static final String BUCKET = "orderflow-catalog-imports";

  private final String run = UUID.randomUUID().toString();
  /** SKUs nuevos en cada corrida: {@code ^[A-Z0-9-]{3,32}$}. */
  private final String prefix = "F15-" + run.substring(0, 8).toUpperCase(Locale.ROOT);

  @AfterEach
  void removeImportedProducts() {
    Db.update(Db.Database.CATALOG, "DELETE FROM product WHERE sku LIKE ?", prefix + "-%");
  }

  @Test
  void lasFilasValidasSeImportanYLasInvalidasVanAlArchivoDeErrores() {
    String file = "f15-" + run + ".csv";
    String a = prefix + "-A";
    String b = prefix + "-B";
    String c = prefix + "-C";
    String csv =
        String.join(
            "\n",
            "sku,name,basePrice",
            a + ",Producto F15 A,10.00",
            b + ",Producto F15 B,20.00",
            prefix + "-X,Precio negativo,-1",
            c + ",Producto F15 C,30.00",
            a + ",Producto F15 A repetido,40.00",
            "");

    Aws.S3.putObject(
        r -> r.bucket(BUCKET).key("incoming/" + file).contentType("text/csv"),
        RequestBody.fromString(csv));

    String token = Tokens.buyer("acme");
    String errorsKey = "errors/" + file + ".errors.csv";
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              assertThat(catalogSkus(token)).contains(a, b, c).doesNotContain(prefix + "-X");
              assertThat(Aws.head(BUCKET, errorsKey)).as(errorsKey).isNotNull();
            });

    List<String> errorRows =
        Aws.S3.getObjectAsBytes(r -> r.bucket(BUCKET).key(errorsKey)).asUtf8String().lines().toList();
    assertThat(errorRows).first().isEqualTo("line,reason");
    assertThat(errorRows.subList(1, errorRows.size()))
        .containsExactly("4,INVALID_PRICE", "6,DUPLICATE_SKU_IN_FILE");
  }

  /** Todas las páginas de {@code GET /api/catalog/products}. */
  private static List<String> catalogSkus(String token) {
    var skus = new ArrayList<String>();
    for (int page = 0; ; page++) {
      List<Map<String, Object>> content =
          Api.as(token)
              .queryParam("page", page)
              .queryParam("size", 100)
              .get("/api/catalog/products")
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getList("content");
      if (content.isEmpty()) {
        return skus;
      }
      content.forEach(p -> skus.add((String) p.get("sku")));
    }
  }
}
