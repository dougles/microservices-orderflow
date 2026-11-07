# OrderFlow — Catálogo de fallos v1 (CONGELADO)

**Definición de terminado del proyecto:** todos los F de esta tabla pasan, en dos corridas seguidas de la suite completa.
Nada que no esté aquí se construye. Cada fila es un test (una clase por F). Los valores (timeouts, umbrales) son los de SPEC §12 y §15.
Claude no puede editar este archivo (hook). Si un F es imposible o contradice a SPEC → ADR.

Convenciones:
- **Nivel IT** = test `*IT.java` del módulo indicado. **Nivel E2E** = `*E2E.java` en `e2e-tests`, stack con `ORDERFLOW_PROFILE=e2e`.
- "Sin cambios" = el valor medido después es igual al medido antes del test (deltas, no valores absolutos).
- "Límite" = timeout de Awaitility para alcanzar el resultado.
- Nombre de la clase: `F07InventoryDownE2E`, `F13ConcurrentLastUnitIT`, etc.

| ID | Escenario | Preparación / inyección | Resultado esperado | Límite | Nivel | Fase |
|---|---|---|---|---|---|---|
| F00 | Camino feliz | buyer-acme: SKU-0001 ×2 y SKU-0002 ×3, entrega hoy+7 | status CONFIRMED; stock −2 y −3; `used` de ACME + total; `booked` de la fecha +1; existe `s3://orderflow-invoices/invoices/ACME/{orderId}.pdf` con content-type `application/pdf` | 60 s (CONFIRMED) + 60 s (PDF) | E2E | 4 |
| F01 | Reintento con la misma Idempotency-Key | Mismo body y misma key: 2 POST secuenciales y luego 2 concurrentes | Las 4 respuestas son 202 con el mismo `orderId`; existe 1 solo pedido | — | IT order | 2 |
| F02 | Misma key, body distinto | POST con key K; POST con key K y otra quantity | 2.º → 422 `IDEMPOTENCY_KEY_REUSED`; el pedido original no cambia | — | IT order | 2 |
| F03 | Sin stock | SKU-0003 ×5000 (stock 1000) | REJECTED, reason OUT_OF_STOCK; stock y crédito sin cambios | 30 s | E2E | 3 |
| F04 | Crédito excedido | buyer-globex: SKU-0020 ×3 (total 600.00 > límite 500.00) | REJECTED, reason CREDIT_LIMIT_EXCEEDED; stock y crédito sin cambios | 30 s | E2E | 3 |
| F05 | Sin capacidad de despacho | Vía JDBC: `max_orders = booked` para la fecha D; pedido con entrega D | CANCELLED, reason SHIPPING_NO_CAPACITY; stock y crédito sin cambios | 30 s | E2E | 3 |
| F06 | Mensaje duplicado | Publicar 2 veces el mismo ReserveStock (mismo `message_id`) | Stock descontado 1 vez; exactamente 1 StockReserved en `inventory.replies` | 15 s | IT inventory | 3 |
| F07 | Servicio caído antes del paso | `pause inventory-service`; POST; esperar 25 s; `unpause` | CANCELLED, reason STEP_TIMEOUT; stock y crédito sin cambios | 90 s tras unpause | E2E | 3 |
| F08 | Respuesta tardía tras timeout | `pause credit-service`; POST; esperar 25 s; `unpause` | CANCELLED, reason STEP_TIMEOUT; stock y crédito sin cambios (la reserva tardía se liberó) | 90 s tras unpause | E2E | 3 |
| F09 | Caída entre commit y publicación | Fila de outbox commiteada con el publisher detenido; arrancar el publisher | Exactamente 1 registro en Kafka con header `message_id` = id de la fila; `published_at` no nulo | 15 s | IT messaging-lib | 1 |
| F10 | Poison message y DLT | (a) JSON inválido con headers válidos en `inventory.commands`, seguido de un ReserveStock válido. (b) `pause` del contenedor Postgres 20 s mientras llega un ReserveStock válido; luego `unpause` y replay vía `POST /ops/dlt/inventory.commands/replay` con rol ops | (a) va a `inventory.commands.DLT` tras 1 intento y el siguiente mensaje se procesa. (b) va al DLT tras 4 intentos; tras el replay se procesa: stock descontado y 1 StockReserved | 60 s | IT inventory | 3 |
| F11 | Kafka caído | `pause kafka`; POST; verificar vía JDBC ≥ 1 fila sin publicar en el outbox de `orders`; a los 8 s `unpause` | POST → 202 con Kafka caído; luego CONFIRMED | 90 s tras unpause | E2E | 3 |
| F12 | Catálogo caído / circuit breaker | `pause` de las 2 réplicas de catalog-service; 10 POST secuenciales; luego 20 POST; `unpause`; repetir un POST con una key ya usada | Primeros 10: 503 `CATALOG_UNAVAILABLE`, cada uno ≤ 3 s. Siguientes 20: 503 en < 100 ms cada uno. Tras unpause y 30 s, la key reutilizada → 202 (no se había guardado) | — | E2E | 2 |
| F13 | Concurrencia sobre la última unidad | Stock de un SKU = 1; 10 ReserveStock concurrentes de pedidos distintos, 1 unidad cada uno | Exactamente 1 StockReserved y 9 StockRejected(OUT_OF_STOCK); `available` = 0, nunca negativo | 30 s | IT inventory | 3 |
| F14 | Lambda de factura falla | Enviar a `invoice-jobs` un body que no es JSON | El mensaje llega a `invoice-jobs-dlq`; la alerta `InvoiceDlqNotEmpty` queda FIRING en Prometheus. Limpieza: purgar la DLQ al final | 4 min (DLQ), 5 min (alerta) | E2E | 4 |
| F15 | CSV con filas inválidas | Subir a `incoming/f15-<uuid>.csv`: 3 filas válidas con SKUs nuevos, 1 con basePrice `-1`, 1 que repite un SKU del mismo archivo | Los 3 SKUs nuevos aparecen en `GET /api/catalog/products`; existe `errors/f15-<uuid>.csv.errors.csv` con exactamente 2 filas: INVALID_PRICE y DUPLICATE_SKU_IN_FILE con sus números de línea | 60 s | E2E | 4 |
| F16 | Acceso entre empresas | Pedido de ACME | buyer-globex: GET → 404 `NOT_FOUND`; su listado no lo incluye. ops: GET → 200 | — | IT order | 2 |
| F17 | Rate limit por empresa | 100 GET `/api/orders` concurrentes como buyer-acme y, a la vez, 5 como buyer-globex | ≥ 1 respuesta 429 para ACME; las 5 de GLOBEX → 200 | — | E2E | 2 |
| F18 | Traza distribuida | Pedido como en F00 hasta CONFIRMED | En la API de Jaeger existe una traza con atributo `order.id` = orderId que contiene spans de gateway, order-service, inventory-service, credit-service y shipping-service | 60 s | E2E | 5 |
| F19 | Compensación sin respuesta | Saga llevada a AWAITING_SHIPMENT con stock y crédito reservados (respuestas inyectadas en los topics); luego ShipmentRejected; se responde StockReleased pero nunca CreditReleased. Propiedades del test: `compensation-retry-interval=PT1S`, `max-compensation-attempts=5` | Saga en COMPENSATION_FAILED; ReleaseCredit emitido 6 veces (1 + 5 reintentos); `orderflow.saga.compensation.failed` +1; API: status CANCELLED, reason SHIPPING_NO_CAPACITY | 30 s | IT order | 3 |
