#!/bin/bash
# Buckets, colas y DLQs (SPEC §11). Idempotente. Las Lambdas se agregan en Fase 4.
set -euo pipefail

for bucket in orderflow-catalog-imports orderflow-invoices; do
  awslocal s3api head-bucket --bucket "$bucket" 2>/dev/null || awslocal s3 mb "s3://$bucket"
done

for queue in catalog-import invoice-jobs; do
  dlq_url=$(awslocal sqs create-queue --queue-name "$queue-dlq" --query QueueUrl --output text)
  dlq_arn=$(awslocal sqs get-queue-attributes --queue-url "$dlq_url" --attribute-names QueueArn \
    --query Attributes.QueueArn --output text)
  awslocal sqs create-queue --queue-name "$queue" --attributes \
    "{\"VisibilityTimeout\":\"30\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}"
done
