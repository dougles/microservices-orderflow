---
name: outbox
description: Implementar y usar el outbox transaccional de messaging-lib (publicación a Kafka o SQS con polling, FOR UPDATE SKIP LOCKED y continuidad de traza). Usar siempre que un servicio deba emitir un mensaje como efecto de un cambio en su base.
---

# Outbox transaccional

Decisiones (SPEC §7, §10): outbox en la base de cada servicio, publisher por polling, destinos KAFKA o SQS, `message_id` = id de la fila, retención de publicados 7 días.

## Regla de uso
**Nunca** llamar a `KafkaTemplate` ni a `SqsTemplate` desde código de negocio. Siempre:

```java
@Transactional
public void algo(...) {
  repo.save(...);
  outbox.enqueueKafka(Topics.INVENTORY_COMMANDS, orderId.toString(), new ReserveStock(orderId, items));
  // o: outbox.enqueueSqs(Queues.INVOICE_JOBS, new InvoiceJob(...));
}
```
`OutboxWriter` usa `@Transactional(propagation = MANDATORY)`: falla si no hay transacción activa.

## Migración (en messaging-lib: `src/main/resources/db/messaging/V0_1__outbox.sql`)

```sql
CREATE TABLE outbox_message (
  id               UUID PRIMARY KEY,
  destination_type VARCHAR(10)  NOT NULL CHECK (destination_type IN ('KAFKA','SQS')),
  destination      VARCHAR(200) NOT NULL,
  message_key      VARCHAR(200),
  message_type     VARCHAR(100) NOT NULL,
  payload          JSONB        NOT NULL,
  traceparent      VARCHAR(100),
  created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
  published_at     TIMESTAMPTZ,
  attempts         INT          NOT NULL DEFAULT 0,
  last_error       TEXT
);
CREATE INDEX idx_outbox_unpublished ON outbox_message (created_at) WHERE published_at IS NULL;
```
Las versiones `V0_x` de messaging-lib corren antes que las `V1+` del servicio. El servicio incluye `classpath:db/messaging` en `spring.flyway.locations`.

## Escritura
- `id = UUID.randomUUID()`; `message_type` = nombre simple del record; `payload` = JSON del record.
- `traceparent`: inyectar el contexto del span actual con el `Propagator` de Micrometer Tracing en un mapa y guardar el valor de `traceparent` (null si no hay span).

## Publisher

```java
@Scheduled(fixedDelayString = "${orderflow.outbox.poll-interval}")
@Transactional
public void publishBatch() {
  List<OutboxRow> rows = jdbc.query("""
      SELECT * FROM outbox_message
      WHERE published_at IS NULL
      ORDER BY created_at
      LIMIT ?
      FOR UPDATE SKIP LOCKED
      """, mapper, batchSize);
  for (OutboxRow row : rows) {
    try {
      send(row);                       // bloqueante, ver abajo
      markPublished(row.id());         // published_at = now()
    } catch (Exception e) {
      markFailed(row.id(), e);         // attempts + 1, last_error
      break;                           // no adelantar mensajes posteriores en este ciclo
    }
  }
}
```
- **Kafka:** abrir un span hijo del contexto extraído de `row.traceparent` (`propagator.extract` + `tracer.nextSpan(...)`), y dentro de su scope `kafkaTemplate.send(ProducerRecord)` con headers `message_id` y `message_type`, y `.get(10, SECONDS)`. **No** escribir el header `traceparent` a mano: la observación de KafkaTemplate lo inyecta desde el span activo. Así la traza continúa (SPEC §13).
- **SQS:** `SqsTemplate` con atributos `message_id`, `message_type` y `traceparent` (copiado de la fila). La autoconfiguración SQS es condicional: `@ConditionalOnClass(SqsTemplate.class)`, porque la dependencia es `<optional>` en messaging-lib (SPEC §2).
- Habilitar `@EnableScheduling` desde la autoconfiguración de la librería.

## Métrica y limpieza
- Gauge `orderflow.outbox.pending` = `SELECT count(*) WHERE published_at IS NULL` (con cache de 5 s para no consultar en cada scrape).
- Job diario: `DELETE FROM outbox_message WHERE published_at < now() - <orderflow.outbox.retention>`.

## Garantías (no agregar más)
- At-least-once: si el proceso muere entre `send` y `markPublished`, el mensaje se reenvía con el **mismo** `message_id`; el inbox del consumidor lo descarta.
- El orden por key entre instancias no está garantizado (SPEC §17.1). No agregues locks por key.

## Test F09 (IT messaging-lib)
Contexto mínimo con una app de prueba en `src/test`. Insertar una fila con el publisher deshabilitado (propiedad de test), habilitarlo y verificar exactamente 1 registro en Kafka con `message_id` = id y `published_at` no nulo.
