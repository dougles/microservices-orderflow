package com.orderflow.invoicefn;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.orderflow.contracts.InvoiceJob;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * invoice-fn: por cada InvoiceJob, PDF en {@code invoices/{companyId}/{orderId}.pdf}. Un mensaje
 * que falla va a {@code batchItemFailures}; el resto del batch sigue (SPEC §11).
 */
public class InvoiceHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

  static final String BUCKET = "orderflow-invoices";

  private final S3Client s3;

  public InvoiceHandler() {
    this(Clients.S3);
  }

  InvoiceHandler(S3Client s3) {
    this.s3 = s3;
  }

  static String key(InvoiceJob job) {
    return "invoices/%s/%s.pdf".formatted(job.companyId(), job.orderId());
  }

  @Override
  public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
    var failures = new ArrayList<SQSBatchResponse.BatchItemFailure>();
    for (SQSEvent.SQSMessage message : event.getRecords()) {
      var fields = new HashMap<String, String>();
      fields.put("messageId", attribute(message, "message_id"));
      fields.put("traceId", JsonLog.traceId(attribute(message, "traceparent")));
      try {
        InvoiceJob job = InvoiceJobReader.read(message.getBody());
        fields.put("orderId", job.orderId().toString());
        fields.put("companyId", job.companyId());
        String key = key(job);
        s3.putObject(
            b -> b.bucket(BUCKET).key(key).contentType("application/pdf"),
            RequestBody.fromBytes(InvoicePdf.render(job)));
        fields.put("key", key);
        JsonLog.info("factura generada", fields);
      } catch (RuntimeException e) {
        fields.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        JsonLog.error("no se pudo generar la factura", fields);
        failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
      }
    }
    return new SQSBatchResponse(failures);
  }

  private static String attribute(SQSEvent.SQSMessage message, String name) {
    Map<String, SQSEvent.MessageAttribute> attributes = message.getMessageAttributes();
    if (attributes == null || attributes.get(name) == null) {
      return null;
    }
    return attributes.get(name).getStringValue();
  }

  /** Creado una sola vez por contenedor de Lambda, y solo si se usa el constructor público. */
  private static final class Clients {

    static final S3Client S3 = build();

    private static S3Client build() {
      var builder = S3Client.builder().forcePathStyle(true);
      String endpoint = System.getenv("AWS_ENDPOINT_URL");
      if (endpoint != null && !endpoint.isBlank()) {
        builder.endpointOverride(URI.create(endpoint));
      }
      return builder.build();
    }
  }
}
