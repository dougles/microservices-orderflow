package com.orderflow.catalogimportfn;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.CatalogImportRow;
import com.orderflow.contracts.Queues;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

/**
 * catalog-import-fn: valida el CSV, manda las filas válidas a {@code catalog-import} y, si hubo
 * errores, escribe {@code errors/<nombre>.errors.csv} (SPEC §11).
 */
public class CatalogImportHandler implements RequestHandler<S3Event, Void> {

  static final String BUCKET = "orderflow-catalog-imports";
  static final int BATCH_SIZE = 10;
  static final int BATCH_RETRIES = 3;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final S3Client s3;
  private final SqsClient sqs;

  public CatalogImportHandler() {
    this(Clients.S3, Clients.SQS);
  }

  CatalogImportHandler(S3Client s3, SqsClient sqs) {
    this.s3 = s3;
    this.sqs = sqs;
  }

  /** Determinista: reprocesar el mismo objeto produce los mismos ids (SPEC §11). */
  static UUID messageId(String key, String etag, int line) {
    return UUID.nameUUIDFromBytes((key + etag + line).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public Void handleRequest(S3Event event, Context context) {
    for (S3EventNotificationRecord record : event.getRecords()) {
      String bucket = record.getS3().getBucket().getName();
      String key = record.getS3().getObject().getUrlDecodedKey();
      if (!key.startsWith(CatalogCsv.INCOMING) || !key.endsWith(".csv")) {
        JsonLog.info("objeto ignorado", Map.of("sourceFile", key));
        continue;
      }
      importFile(bucket, key);
    }
    return null;
  }

  private void importFile(String bucket, String key) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("sourceFile", key);
    try {
      var object = s3.getObjectAsBytes(b -> b.bucket(bucket).key(key));
      String etag = object.response().eTag().replace("\"", "");
      CatalogCsv.Result result = CatalogCsv.parse(object.asUtf8String());
      send(result.valid(), key, etag);
      if (!result.errors().isEmpty()) {
        String errorsKey = CatalogCsv.errorsKey(key);
        s3.putObject(
            b -> b.bucket(bucket).key(errorsKey).contentType("text/csv"),
            RequestBody.fromString(CatalogCsv.errorsCsv(result.errors()), StandardCharsets.UTF_8));
        fields.put("errorsFile", errorsKey);
      }
      fields.put("validRows", result.valid().size());
      fields.put("invalidRows", result.errors().size());
      JsonLog.info("importación procesada", fields);
    } catch (RuntimeException e) {
      fields.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
      JsonLog.error("importación fallida", fields);
      throw e;
    }
  }

  private void send(List<CatalogCsv.Row> rows, String key, String etag) {
    if (rows.isEmpty()) {
      return;
    }
    String queueUrl = sqs.getQueueUrl(b -> b.queueName(Queues.CATALOG_IMPORT)).queueUrl();
    for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
      var entries = new ArrayList<SendMessageBatchRequestEntry>();
      for (CatalogCsv.Row row : rows.subList(from, Math.min(from + BATCH_SIZE, rows.size()))) {
        entries.add(entry(row, key, etag));
      }
      sendBatch(queueUrl, entries);
    }
  }

  /** Reintenta solo las entradas fallidas, hasta {@link #BATCH_RETRIES} veces. */
  private void sendBatch(String queueUrl, List<SendMessageBatchRequestEntry> entries) {
    List<SendMessageBatchRequestEntry> pending = entries;
    for (int attempt = 0; ; attempt++) {
      List<SendMessageBatchRequestEntry> toSend = pending;
      SendMessageBatchResponse response =
          sqs.sendMessageBatch(b -> b.queueUrl(queueUrl).entries(toSend));
      if (response.failed().isEmpty()) {
        return;
      }
      var failedIds = new HashSet<String>();
      response.failed().forEach(f -> failedIds.add(f.id()));
      if (attempt == BATCH_RETRIES) {
        throw new IllegalStateException(
            "SendMessageBatch con %d entradas fallidas tras %d reintentos: %s"
                .formatted(failedIds.size(), BATCH_RETRIES, response.failed().getFirst().message()));
      }
      pending = toSend.stream().filter(e -> failedIds.contains(e.id())).toList();
    }
  }

  private static SendMessageBatchRequestEntry entry(CatalogCsv.Row row, String key, String etag) {
    var message = new CatalogImportRow(row.sku(), row.name(), row.basePrice(), key, row.line());
    String body;
    try {
      body = MAPPER.writeValueAsString(message);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    // traceparent no se envía: un evento de S3 no trae contexto de traza y SQS rechaza
    // atributos con valor vacío.
    return SendMessageBatchRequestEntry.builder()
        .id("line-" + row.line())
        .messageBody(body)
        .messageAttributes(
            Map.of(
                "message_id", string(messageId(key, etag, row.line()).toString()),
                "message_type", string(CatalogImportRow.class.getSimpleName())))
        .build();
  }

  private static MessageAttributeValue string(String value) {
    return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
  }

  /** Creados una sola vez por contenedor de Lambda, y solo si se usa el constructor público. */
  private static final class Clients {

    static final S3Client S3 = s3();
    static final SqsClient SQS = sqs();

    private static URI endpoint() {
      String endpoint = System.getenv("AWS_ENDPOINT_URL");
      return endpoint == null || endpoint.isBlank() ? null : URI.create(endpoint);
    }

    private static S3Client s3() {
      var builder = S3Client.builder().forcePathStyle(true);
      if (endpoint() != null) {
        builder.endpointOverride(endpoint());
      }
      return builder.build();
    }

    private static SqsClient sqs() {
      var builder = SqsClient.builder();
      if (endpoint() != null) {
        builder.endpointOverride(endpoint());
      }
      return builder.build();
    }
  }
}
