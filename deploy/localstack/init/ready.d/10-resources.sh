#!/bin/bash
# Buckets, colas, DLQs y Lambdas (SPEC §11). Idempotente.
# Los jars se montan desde <modulo>/target: deben existir antes de `docker compose up`
# (./mvnw -q package -DskipTests).
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

# ---------- Lambdas ----------
ROLE=arn:aws:iam::000000000000:role/orderflow-lambda
ENDPOINT=http://localstack:4566

# El jar sombreado del módulo (no el original-*.jar que deja maven-shade-plugin).
jar_of() {
  local jar
  jar=$(find "/opt/lambdas/$1" -maxdepth 1 -name "$1-*.jar" ! -name 'original-*' | head -n 1)
  if [ -z "$jar" ]; then
    echo "falta el jar de $1 en /opt/lambdas/$1: correr ./mvnw -q package -DskipTests" >&2
    exit 1
  fi
  echo "$jar"
}

# deploy_function <nombre> <handler> <timeout>
deploy_function() {
  local name=$1 handler=$2 timeout=$3 jar
  jar=$(jar_of "$name")
  if awslocal lambda get-function --function-name "$name" >/dev/null 2>&1; then
    awslocal lambda update-function-code --function-name "$name" --zip-file "fileb://$jar" >/dev/null
  else
    awslocal lambda create-function --function-name "$name" \
      --runtime java21 --handler "$handler" --role "$ROLE" \
      --memory-size 512 --timeout "$timeout" \
      --environment "Variables={AWS_ENDPOINT_URL=$ENDPOINT}" \
      --zip-file "fileb://$jar" >/dev/null
  fi
  awslocal lambda wait function-active-v2 --function-name "$name"
}

deploy_function invoice-fn com.orderflow.invoicefn.InvoiceHandler 20
deploy_function catalog-import-fn com.orderflow.catalogimportfn.CatalogImportHandler 60

# invoice-fn consume invoice-jobs: batch 5 y fallos parciales.
invoice_jobs_arn=$(awslocal sqs get-queue-attributes \
  --queue-url "$(awslocal sqs get-queue-url --queue-name invoice-jobs --query QueueUrl --output text)" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)
mappings=$(awslocal lambda list-event-source-mappings --function-name invoice-fn \
  --event-source-arn "$invoice_jobs_arn" --query 'length(EventSourceMappings)' --output text)
if [ "$mappings" = "0" ]; then
  awslocal lambda create-event-source-mapping --function-name invoice-fn \
    --event-source-arn "$invoice_jobs_arn" --batch-size 5 \
    --function-response-types ReportBatchItemFailures >/dev/null
fi

# catalog-import-fn se dispara con cada .csv creado bajo incoming/.
awslocal lambda add-permission --function-name catalog-import-fn \
  --statement-id s3-catalog-imports --action lambda:InvokeFunction \
  --principal s3.amazonaws.com --source-arn arn:aws:s3:::orderflow-catalog-imports \
  >/dev/null 2>&1 || true
catalog_import_fn_arn=$(awslocal lambda get-function --function-name catalog-import-fn \
  --query Configuration.FunctionArn --output text)
awslocal s3api put-bucket-notification-configuration --bucket orderflow-catalog-imports \
  --notification-configuration "{
    \"LambdaFunctionConfigurations\": [{
      \"LambdaFunctionArn\": \"$catalog_import_fn_arn\",
      \"Events\": [\"s3:ObjectCreated:*\"],
      \"Filter\": {\"Key\": {\"FilterRules\": [
        {\"Name\": \"prefix\", \"Value\": \"incoming/\"},
        {\"Name\": \"suffix\", \"Value\": \".csv\"}
      ]}}
    }]
  }"
