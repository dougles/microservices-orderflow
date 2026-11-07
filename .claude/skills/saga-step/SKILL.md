---
name: saga-step
description: Implementar o modificar la saga orquestada de order-service (estados, transiciones, timeouts, compensaciones y respuestas tardías) según SPEC §8.
---

# Saga orquestada (order-service)

La **tabla de transiciones de SPEC §8.3 y las reglas de §8.4 son la especificación**. Este archivo solo dice cómo implementarlas. Si una combinación (estado, evento) no está en SPEC, cae en la regla "cualquier otra combinación" de §8.4: no inventes transiciones.

## Tabla (`V2__saga.sql` en order-service; V1 = pedidos e idempotencia)

```sql
CREATE TABLE saga (
  order_id                UUID PRIMARY KEY REFERENCES orders(id),
  state                   VARCHAR(30) NOT NULL,
  failure_reason          VARCHAR(40),
  step_deadline           TIMESTAMPTZ,
  stock_reserved          BOOLEAN NOT NULL DEFAULT false,
  credit_reserved         BOOLEAN NOT NULL DEFAULT false,
  stock_release_pending   BOOLEAN NOT NULL DEFAULT false,
  credit_release_pending  BOOLEAN NOT NULL DEFAULT false,
  shipment_cancel_pending BOOLEAN NOT NULL DEFAULT false,
  compensation_attempts   INT     NOT NULL DEFAULT 0,
  updated_at              TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_saga_deadline ON saga (step_deadline)
  WHERE state IN ('AWAITING_STOCK','AWAITING_CREDIT','AWAITING_SHIPMENT','COMPENSATING');
```
`step_deadline` y `updated_at` se setean desde la aplicación (inyecta `Clock` para poder testear).

## Estructura
- `SagaState` (enum), `FailureReason` (enum), `Saga` (entidad).
- `SagaOrchestrator`: un método por evento (`onStockReserved(orderId)`, `onCreditRejected(orderId, reason)`, `onTimeout(orderId)`, …). Cada método:
  1. `@Transactional`.
  2. Carga la saga con lock: `@Lock(PESSIMISTIC_WRITE)` (`SELECT … FOR UPDATE`).
  3. Aplica la fila de §8.3 o la regla de §8.4 que corresponda.
  4. Emite comandos **solo** vía outbox (skill `outbox`).
  5. Al llegar a un estado final: `step_deadline = null` y counter `orderflow.saga.finished{state}`.
- Listener de replies (`inventory.replies`, `credit.replies`, `shipping.replies`) según la skill `kafka-consumer`; despacha por `message_type` al orquestador.

## Compensación (§8.2)
`startCompensation(saga, reason, releaseCurrentStep)`:
- Para cada recurso a liberar: marca `*_pending = true` y emite el comando (`ReleaseStock`, `ReleaseCredit`, `CancelShipment`).
- Recursos = los marcados `*_reserved` **más**, si es timeout, el del paso en curso (AWAITING_STOCK → stock, AWAITING_CREDIT → crédito, AWAITING_SHIPMENT → despacho).
- `state = COMPENSATING`, `step_deadline = now + compensation-retry-interval`.
- Respuesta de liberación: limpia su `*_pending`. Si no queda ninguno → estado final según reason (§5.2: REJECTED o CANCELLED).
- Deadline en COMPENSATING: si `compensation_attempts < max` → `+1` y re-emitir **solo** los pendientes; si no → `COMPENSATION_FAILED` + counter `orderflow.saga.compensation.failed`.

Nota: el despacho no tiene flag `*_reserved` porque ShipmentScheduled lleva directo a CONFIRMED (estado final que no compensa).

## Scanner de timeouts

```java
@Scheduled(fixedDelayString = "${orderflow.saga.timeout-check-interval}")
public void scan() {
  // tx corta: SELECT order_id FROM saga
  //   WHERE step_deadline < now() AND state IN (...) ORDER BY step_deadline LIMIT 50
  //   FOR UPDATE SKIP LOCKED
  // por cada id: orchestrator.onTimeout(id) en su propia transacción
}
```
`onTimeout` vuelve a verificar estado y deadline bajo lock (puede haber llegado una respuesta entre medio).

## Respuestas tardías (§8.4)
Centraliza en un único método `handleLateOrUnexpected(saga, event)` para que la regla exista en un solo lugar. Nunca lanza excepción: log WARN + `orderflow.saga.unexpected.reply` para lo no listado.

## Creación (desde POST /api/orders)
Misma transacción que el pedido: `state = AWAITING_STOCK`, deadline, outbox `ReserveStock`. El span de esta operación lleva el atributo `order.id` (F18).

## InvoiceJob al confirmar
En la transición a CONFIRMED: `outbox.enqueueSqs(Queues.INVOICE_JOBS, new InvoiceJob(...))` con líneas, total, moneda y `confirmedAt`.

## Tests
- Unitarios del orquestador sin Spring: todas las filas de §8.3 y casos de §8.4, con `Clock` fijo y un outbox falso en memoria.
- F19 (IT order): Testcontainers Kafka + Postgres; inyecta replies en los topics `*.replies` y lee comandos de `credit.commands`.
