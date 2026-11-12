package com.orderflow.catalog.imports;

import com.orderflow.contracts.CatalogImportRow;
import com.orderflow.contracts.Queues;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumidor de {@code catalog-import} (SPEC §11). Sin inbox: el upsert por sku es idempotente
 * (SPEC §10). Si lanza, el mensaje no se borra: SQS lo reintenta y luego lo mueve a la DLQ.
 */
@Component
class CatalogImportListener {

  private static final Logger log = LoggerFactory.getLogger(CatalogImportListener.class);

  private final JdbcTemplate jdbc;

  CatalogImportListener(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @SqsListener(Queues.CATALOG_IMPORT)
  void onMessage(String body, @Header(name = "message_id", required = false) String messageId) {
    try (var ignored = MDC.putCloseable("messageId", messageId)) {
      CatalogImportRow row = JsonMapper.shared().readValue(body, CatalogImportRow.class);
      jdbc.update(
          """
          INSERT INTO product (sku, name, base_price, active) VALUES (?, ?, ?, TRUE)
          ON CONFLICT (sku) DO UPDATE
            SET name = EXCLUDED.name, base_price = EXCLUDED.base_price, active = TRUE
          """,
          row.sku(),
          row.name(),
          row.basePrice());
      log.info("producto importado sku={} sourceFile={} line={}", row.sku(), row.sourceFile(), row.line());
    }
  }
}
