---
name: aws-lambda
description: Implementar las Lambdas (catalog-import-fn, invoice-fn), su despliegue en LocalStack y el consumo/monitoreo SQS en servicios Spring (catalog-service, order-service).
---

# Lambdas y SQS

Recursos, nombres y límites: SPEC §11. Formato CSV y códigos de error: SPEC §11. Contratos: `CatalogImportRow` e `InvoiceJob` (SPEC §7.2).

## Lambdas (Java 21, sin Spring)
- Módulos `catalog-import-fn` e `invoice-fn`. Dependen de `contracts`. Jar con `maven-shade-plugin`.
- Clientes AWS SDK v2 creados **una vez** (campo estático). Endpoint desde `AWS_ENDPOINT_URL`; `forcePathStyle(true)` en S3.
- Logs: una línea JSON por evento a stdout con `traceId` (del atributo `traceparent`, segundo campo separado por `-`), `orderId` o `sourceFile`, y nivel.
- Lógica pura (parseo, validación, render) en clases sin AWS, testeadas con `*Test.java`.

### catalog-import-fn (`RequestHandler<S3Event, Void>`)
1. Por cada registro: leer el objeto (bucket `orderflow-catalog-imports`, prefijo `incoming/`).
2. Validar encabezado y cantidad de filas; luego cada fila (SPEC §9: reglas de producto) y duplicados dentro del archivo.
3. Filas válidas → `SendMessageBatch` en lotes de 10 a `catalog-import`, con atributos `message_id` (UUID determinista: `UUID.nameUUIDFromBytes((key + etag + linea).getBytes(UTF_8))`), `message_type=CatalogImportRow` y `traceparent` vacío. Reintentar las entradas fallidas del batch hasta 3 veces y luego lanzar excepción.
4. Si hubo errores: `errors/<nombreOriginal>.errors.csv` con encabezado `line,reason`.

### invoice-fn (`RequestHandler<SQSEvent, SQSBatchResponse>`)
- Por mensaje: parsear `InvoiceJob`; render PDF con PDFBox (empresa, pedido, líneas, total, moneda, fecha); `PutObject` en `orderflow-invoices/invoices/{companyId}/{orderId}.pdf` con `contentType("application/pdf")`.
- Error en un mensaje → agregar su `messageId` a `batchItemFailures` y seguir con el resto. Nunca fallar el batch completo por un mensaje.
- Un body no parseable es un fallo más: tras 3 recepciones, SQS lo mueve a la DLQ (F14).

## Despliegue en LocalStack
Script `deploy/localstack/init/ready.d/10-resources.sh` (Fase 0: buckets y colas; Fase 4: Lambdas), con `awslocal`, idempotente (no fallar si ya existe):
- Colas con `RedrivePolicy` (`maxReceiveCount=3`) y `VisibilityTimeout=30`.
- `lambda create-function` con runtime `java21`, el jar montado desde `target/`, memoria y timeout de SPEC §11, `Environment` con `AWS_ENDPOINT_URL`.
- invoice-fn: `create-event-source-mapping` con `--batch-size 5 --function-response-types ReportBatchItemFailures`.
- catalog-import-fn: permiso + `put-bucket-notification-configuration` para `s3:ObjectCreated:*` con filtro prefijo `incoming/` y sufijo `.csv`.
- Los jars deben existir antes de `docker compose up` (`./mvnw -q package -DskipTests`).

## catalog-service: consumidor SQS
- `@SqsListener("catalog-import")` (Spring Cloud AWS). Upsert por sku: inserta o actualiza `name` y `base_price` y deja `active = true`.
- Excepción → el mensaje no se borra; SQS reintenta y luego mueve a DLQ. Sin inbox: el upsert es idempotente (SPEC §10).
- Endpoint en config: `spring.cloud.aws.endpoint=http://localstack:4566`, región y credenciales de SPEC §11.

## Monitor de DLQ (order-service y catalog-service)
`@Scheduled(fixedDelayString = "${orderflow.dlq-monitor.interval}")`: `GetQueueAttributes(ApproximateNumberOfMessages)` de su DLQ → gauge `orderflow.sqs.dlq.messages{queue=<nombre>}`. Si la llamada falla, conservar el último valor y loguear WARN.

## Tests
- `*Test.java`: parser y validador del CSV (todas las razones de SPEC §11) y render del PDF (el resultado empieza con `%PDF`).
- `*IT.java` del handler: invoca el handler directamente con S3/SQS reales de Testcontainers LocalStack (el runtime de Lambda no corre en IT).
- F00, F14 y F15 son E2E (skill `failure-test`) contra las Lambdas desplegadas en LocalStack.
