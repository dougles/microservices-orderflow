package com.orderflow.catalogimportfn;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.contracts.Queues;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/** Handler invocado directamente con S3 y SQS reales de LocalStack (skill aws-lambda). */
@Testcontainers
class CatalogImportHandlerIT {

  @Container
  static LocalStackContainer localstack =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
          .withServices("s3", "sqs");

  static S3Client s3;
  static SqsClient sqs;
  static String queueUrl;

  @BeforeAll
  static void resources() {
    var credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey()));
    var region = Region.of(localstack.getRegion());
    s3 =
        S3Client.builder()
            .endpointOverride(localstack.getEndpoint())
            .region(region)
            .credentialsProvider(credentials)
            .forcePathStyle(true)
            .build();
    sqs =
        SqsClient.builder()
            .endpointOverride(localstack.getEndpoint())
            .region(region)
            .credentialsProvider(credentials)
            .build();
    s3.createBucket(b -> b.bucket(CatalogImportHandler.BUCKET));
    queueUrl = sqs.createQueue(b -> b.queueName(Queues.CATALOG_IMPORT)).queueUrl();
  }

  @Test
  void filasValidasALaColaYErroresAlArchivo() throws Exception {
    String key = "incoming/f15-" + UUID.randomUUID() + ".csv";
    upload(
        key,
        """
        sku,name,basePrice
        IT-A,Producto A,10.00
        IT-B,Producto B,20.00
        IT-BAD,Precio negativo,-1
        IT-C,Producto C,30.00
        IT-A,Repetido,40.00
        """);

    invoke(key);

    List<Message> messages = receiveAll(3);
    assertThat(messages).hasSize(3);
    var mapper = new ObjectMapper();
    var skus = new ArrayList<String>();
    for (Message m : messages) {
      var row = mapper.readTree(m.body());
      skus.add(row.get("sku").asText());
      assertThat(row.get("sourceFile").asText()).isEqualTo(key);
      assertThat(m.messageAttributes().get("message_type").stringValue())
          .isEqualTo("CatalogImportRow");
      assertThat(m.messageAttributes().get("message_id").stringValue())
          .isEqualTo(
              CatalogImportHandler.messageId(key, etag(key), row.get("line").asInt()).toString());
    }
    assertThat(skus).containsExactlyInAnyOrder("IT-A", "IT-B", "IT-C");
    assertThat(read(CatalogCsv.errorsKey(key)))
        .isEqualTo("line,reason\n4,INVALID_PRICE\n6,DUPLICATE_SKU_IN_FILE\n");
  }

  @Test
  void sinErroresNoSeEscribeArchivoYLosLotesSonDeDiez() {
    String key = "incoming/ok-" + UUID.randomUUID() + ".csv";
    var csv = new StringBuilder("sku,name,basePrice\n");
    for (int i = 1; i <= 25; i++) {
      csv.append("IT-OK-%02d,Producto %d,%d.00\n".formatted(i, i, i));
    }
    upload(key, csv.toString());

    invoke(key);

    assertThat(receiveAll(25)).hasSize(25);
    assertThat(exists(CatalogCsv.errorsKey(key))).isFalse();
  }

  @Test
  void elMismoObjetoProduceLosMismosMessageIds() {
    String key = "incoming/rep-" + UUID.randomUUID() + ".csv";
    upload(key, "sku,name,basePrice\nIT-REP,Producto,1.00\n");

    invoke(key);
    String first = receiveAll(1).getFirst().messageAttributes().get("message_id").stringValue();
    invoke(key);
    String second = receiveAll(1).getFirst().messageAttributes().get("message_id").stringValue();

    assertThat(second).isEqualTo(first);
  }

  @Test
  void encabezadoInvalidoNoEncolaNada() {
    String key = "incoming/bad-" + UUID.randomUUID() + ".csv";
    upload(key, "codigo,nombre,precio\nIT-X,Producto,1.00\n");

    invoke(key);

    assertThat(receiveAll(0)).isEmpty();
    assertThat(read(CatalogCsv.errorsKey(key))).isEqualTo("line,reason\n1,INVALID_HEADER\n");
  }

  private static void invoke(String key) {
    new CatalogImportHandler(s3, sqs).handleRequest(event(key), null);
  }

  /** Evento como lo arma S3: la key viene URL-encoded. */
  private static S3Event event(String key) {
    var object =
        new S3EventNotification.S3ObjectEntity(
            URLEncoder.encode(key, StandardCharsets.UTF_8),
            1L,
            etag(key),
            null,
            null);
    var bucket =
        new S3EventNotification.S3BucketEntity(
            CatalogImportHandler.BUCKET, null, "arn:aws:s3:::" + CatalogImportHandler.BUCKET);
    var entity = new S3EventNotification.S3Entity("config", bucket, object, "1.0");
    var record =
        new S3EventNotification.S3EventNotificationRecord(
            "us-east-1",
            "ObjectCreated:Put",
            "aws:s3",
            null,
            "2.1",
            null,
            null,
            entity,
            null);
    return new S3Event(List.of(record));
  }

  private static void upload(String key, String content) {
    s3.putObject(b -> b.bucket(CatalogImportHandler.BUCKET).key(key), RequestBody.fromString(content));
  }

  private static String etag(String key) {
    return s3.headObject(b -> b.bucket(CatalogImportHandler.BUCKET).key(key)).eTag().replace("\"", "");
  }

  private static String read(String key) {
    return s3.getObjectAsBytes(b -> b.bucket(CatalogImportHandler.BUCKET).key(key)).asUtf8String();
  }

  private static boolean exists(String key) {
    try {
      s3.headObject(b -> b.bucket(CatalogImportHandler.BUCKET).key(key));
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
      if (e.statusCode() == 404) {
        return false;
      }
      throw e;
    }
  }

  /** Recibe hasta tener {@code expected} mensajes o hasta que la cola deje de devolver. */
  private static List<Message> receiveAll(int expected) {
    var received = new HashMap<String, Message>();
    for (int emptyPolls = 0; emptyPolls < 2 && received.size() <= expected; ) {
      var batch =
          sqs.receiveMessage(
                  b ->
                      b.queueUrl(queueUrl)
                          .maxNumberOfMessages(10)
                          .waitTimeSeconds(1)
                          .messageAttributeNames("All"))
              .messages();
      if (batch.isEmpty()) {
        emptyPolls++;
      }
      for (Message m : batch) {
        received.put(m.messageId(), m);
        sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(m.receiptHandle()));
      }
    }
    return List.copyOf(received.values());
  }
}
