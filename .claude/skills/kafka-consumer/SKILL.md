---
name: kafka-consumer
description: Consumir mensajes de Kafka en OrderFlow con inbox idempotente, error handler con reintentos y DLT, y endpoint de replay del DLT. Usar para cualquier @KafkaListener.
---

# Consumidor Kafka idempotente

Decisiones: SPEC §7.3 (reintentos y DLT), §7.4 y §10 (inbox), §5.5 (replay). Todo lo genérico vive en messaging-lib; cada servicio solo escribe sus handlers.

## Migración (messaging-lib: `db/messaging/V0_2__inbox.sql`)

```sql
CREATE TABLE inbox_message (
  message_id   UUID         NOT NULL,
  consumer     VARCHAR(100) NOT NULL,
  processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
  PRIMARY KEY (message_id, consumer)
);
```

```java
// InboxRepository (messaging-lib)
public boolean firstTime(UUID messageId, String consumer) {
  return jdbc.update("""
      INSERT INTO inbox_message (message_id, consumer) VALUES (?, ?)
      ON CONFLICT DO NOTHING
      """, messageId, consumer) == 1;
}
```
Con duplicados concurrentes, el segundo INSERT espera al primero: si el primero hace commit, devuelve 0 (se ignora); si hace rollback, inserta y procesa. No agregues otros locks.

## Listener

```java
@KafkaListener(topics = Topics.INVENTORY_COMMANDS, groupId = "inventory-service", concurrency = "3")
@Transactional
public void onMessage(ConsumerRecord<String, String> record) {
  UUID messageId = KafkaHeaders.messageId(record);     // helper de messaging-lib
  if (!inbox.firstTime(messageId, "inventory-service")) return;   // duplicado: sin efecto ni respuesta
  String type = KafkaHeaders.messageType(record);
  switch (type) {
    case "ReserveStock" -> handler.reserve(json.read(record.value(), ReserveStock.class));
    case "ReleaseStock" -> handler.release(json.read(record.value(), ReleaseStock.class));
    default -> throw new NonRetryableMessageException("Tipo desconocido: " + type);
  }
}
```
- Valor como `String`; se deserializa según `message_type` (sin deserializador de Kafka que pueda romper el poll).
- Inbox, efecto y respuesta (vía outbox) en **la misma transacción**.
- Header `message_id` ausente o inválido → `NonRetryableMessageException`.
- `consumer` = nombre del consumer group.
- MDC: `messageId` y `orderId` al entrar; limpiar al salir.

## Error handler (messaging-lib, un bean para todos)

```java
@Bean
DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
  var recoverer = new DeadLetterPublishingRecoverer(template,
      (rec, ex) -> new TopicPartition(rec.topic() + ".DLT", rec.partition()));
  var backOff = new ExponentialBackOffWithMaxRetries(3);   // 3 reintentos = 4 intentos
  backOff.setInitialInterval(1_000);
  backOff.setMultiplier(2.0);
  var handler = new DefaultErrorHandler(recoverer, backOff);
  handler.addNotRetryableExceptions(NonRetryableMessageException.class /*, excepción base de Jackson */);
  return handler;
}
```
JSON inválido → la excepción base de Jackson de la versión en uso → no reintentable (F10a).

## Replay del DLT (messaging-lib, se activa en todo servicio que consume)
`POST /ops/dlt/{topic}/replay?max=N` (ROLE_ops; N por defecto 100, máximo 1000):
1. Validar que `{topic}` sea uno de los topics que consume este servicio; si no → 404.
2. Consumer dedicado, group `<servicio>-dlt-replay`, sobre `{topic}.DLT`; leer hasta N registros (poll con timeout de 2 s).
3. Republicar cada uno en `{topic}`, misma key y value, **solo** con los headers `message_id`, `message_type` y `traceparent` (descartar los headers de excepción del DLT).
4. Commit de offsets tras republicar. Responder `{"replayed": n}`.
Si el mensaje ya se había procesado, el inbox lo descarta (mismo `message_id`).

## Tests
- F06, F10 y F13 viven en inventory (skill `failure-test`).
- Para publicar en tests usa un `KafkaTemplate` real contra el contenedor, con headers `message_id`/`message_type`. Para leer respuestas, un consumer de test con group único por test.
