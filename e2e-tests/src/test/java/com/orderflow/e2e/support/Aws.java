package com.orderflow.e2e.support;

import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/** SDK v2 contra LocalStack: {@code http://localhost:4566}, us-east-1, test/test (SPEC §11). */
public final class Aws {

  private static final URI ENDPOINT = URI.create("http://localhost:4566");
  private static final StaticCredentialsProvider CREDENTIALS =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));

  public static final S3Client S3 =
      S3Client.builder()
          .endpointOverride(ENDPOINT)
          .region(Region.US_EAST_1)
          .credentialsProvider(CREDENTIALS)
          .forcePathStyle(true)
          .build();

  public static final SqsClient SQS =
      SqsClient.builder()
          .endpointOverride(ENDPOINT)
          .region(Region.US_EAST_1)
          .credentialsProvider(CREDENTIALS)
          .build();

  private Aws() {}

  public static String queueUrl(String queue) {
    return SQS.getQueueUrl(b -> b.queueName(queue)).queueUrl();
  }

  /** ApproximateNumberOfMessages: los visibles, sin contar los que alguien está procesando. */
  public static long visibleMessages(String queue) {
    String url = queueUrl(queue);
    return Long.parseLong(
        SQS.getQueueAttributes(
                b -> b.queueUrl(url).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
            .attributes()
            .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
  }

  /** El objeto, o null si no existe. */
  public static HeadObjectResponse head(String bucket, String key) {
    try {
      return S3.headObject(b -> b.bucket(bucket).key(key));
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return null;
      }
      throw e;
    }
  }
}
