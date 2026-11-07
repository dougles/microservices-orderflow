# OrderFlow — Especificación v1 (CONGELADA)

Este documento es la **única fuente de verdad sobre QUÉ se construye**: alcance, componentes, contratos (APIs, topics, mensajes, colas, estados), reglas de negocio, valores de configuración.
El **CÓMO** (esquemas internos, código) está en `.claude/skills/`. Si otro archivo contradice a este, el otro tiene un bug → ADR.
Cambios: solo por ADR aceptado por el humano (`docs/adr/`). Claude no puede editar este archivo (hook).

---

## 1. Alcance

Distribuidora mayorista que vende a empresas cliente (B2B). Un comprador de una empresa cliente crea un pedido sobre el catálogo con los precios de su empresa. El pedido reserva stock, consume línea de crédito y agenda despacho. Al confirmarse, se genera una factura PDF.

**Fuera de alcance** (no se implementa ni se "prepara terreno"): Kubernetes, service mesh, CQRS/event sourcing, SNS, GraphQL, frontend, multi-región, multi-moneda, schema registry, Alertmanager, pagos, devoluciones, edición/cancelación de pedidos por el cliente, eventos de dominio públicos (no existe topic `order.events`).

---

## 2. Stack y dependencias

- Java 21. Maven multi-módulo con wrapper (`./mvnw`). Sin Lombok.
- **Versiones:** en Fase 0 se fija en el pom raíz la versión GA más reciente de Spring Boot que sea compatible **a la vez** con un release train de Spring Cloud y con Spring Cloud AWS. Luego no se cambian. Fijadas: Spring Boot 4.0.9, Spring Cloud 2025.1.4, Spring Cloud AWS 4.2.1. Las dependencias fuera de los BOM se fijan en el pom raíz: logstash-logback-encoder 9.0, rest-assured 6.3.0, aws-lambda-java-core 1.4.1, aws-lambda-java-events 3.16.1, pdfbox 3.0.8.
- **Dependencias permitidas** (cualquier otra requiere ADR):

| Módulo | Dependencias |
|---|---|
| Todos los servicios de dominio | spring-boot-starter-web, -data-jpa, -validation, -security, -oauth2-resource-server, -actuator; spring-kafka, spring-boot-kafka; flyway-core, flyway-database-postgresql, spring-boot-flyway; postgresql; micrometer-registry-prometheus; micrometer-tracing-bridge-otel; opentelemetry-exporter-otlp; spring-boot-micrometer-tracing-opentelemetry, spring-boot-opentelemetry; logstash-logback-encoder; spring-cloud-starter-config; spring-cloud-starter-netflix-eureka-client; contracts; messaging-lib |
| order-service (además) | spring-boot-starter-oauth2-client, spring-cloud-starter-loadbalancer, spring-cloud-starter-circuitbreaker-resilience4j, spring-cloud-aws-starter-sqs |
| catalog-service (además) | spring-cloud-aws-starter-sqs |
| gateway | spring-cloud-starter-gateway-server-webflux, spring-boot-starter-oauth2-resource-server, spring-boot-starter-data-redis-reactive, spring-cloud-starter-loadbalancer, spring-cloud-starter-netflix-eureka-client, spring-cloud-starter-config, actuator, micrometer-registry-prometheus, micrometer-tracing-bridge-otel, opentelemetry-exporter-otlp, spring-boot-micrometer-tracing-opentelemetry, spring-boot-opentelemetry, logstash-logback-encoder |
| config-server | spring-cloud-config-server, actuator |
| discovery-server | spring-cloud-starter-netflix-eureka-server, actuator |
| messaging-lib | spring-kafka, spring-jdbc, micrometer-core, micrometer-tracing, jackson-databind de tools.jackson.core (Jackson 3); spring-cloud-aws-starter-sqs como dependencia **`<optional>`** (la autoconfiguración SQS solo se activa si el servicio la trae) |
| contracts | solo JDK (records y constantes) |
| Lambdas | aws-lambda-java-core, aws-lambda-java-events, AWS SDK v2 (s3, sqs), jackson-databind de com.fasterxml.jackson.core (Jackson 2), maven-shade-plugin; pdfbox solo en invoice-fn. Sin Spring |
| Tests | spring-boot-starter-test, spring-boot-testcontainers, spring-boot-webmvc-test, spring-security-test, testcontainers-junit-jupiter, testcontainers-postgresql, testcontainers-kafka, testcontainers-localstack, awaitility, wiremock-standalone; en e2e-tests además rest-assured, postgresql, AWS SDK v2 |

---

## 3. Módulos, puertos y despliegue

### 3.1 Módulos Maven

| Módulo | Tipo | Puerto | Expuesto al host | Instancias |
|---|---|---|---|---|
| contracts | librería | — | — | — |
| messaging-lib | librería | — | — | — |
| config-server | app | 8888 | no | 1 |
| discovery-server | app | 8761 | sí (UI Eureka) | 1 |
| gateway | app | 8080 | sí | 1 |
| catalog-service | app | 8081 | no | **2** |
| order-service | app | 8082 | no | 1 |
| inventory-service | app | 8083 | no | 1 |
| credit-service | app | 8084 | no | 1 |
| shipping-service | app | 8085 | no | 1 |
| catalog-import-fn | Lambda | — | — | — |
| invoice-fn | Lambda | — | — | — |
| e2e-tests | tests | — | — | — |

Paquete base: `com.orderflow.<modulo>` (sin guiones; p. ej. `com.orderflow.inventory`).

### 3.2 Infraestructura (docker compose)

| Contenedor | Puerto | Host | Perfil compose |
|---|---|---|---|
| postgres | 5432 | sí | infra |
| redis | 6379 | no | infra |
| kafka (KRaft, 1 broker) | 9092 | no | infra |
| keycloak (modo dev) | 8180 | sí | infra |
| localstack (s3, sqs, lambda, logs) | 4566 | sí | infra |
| elasticsearch | 9200 | no | obs |
| kibana | 5601 | sí | obs |
| filebeat | — | — | obs |
| prometheus | 9090 | sí | obs |
| grafana | 3000 | sí | obs |
| jaeger (all-in-one, OTLP HTTP 4318) | 16686 UI | sí (UI) | obs |
| config-server, discovery-server, gateway | ver 3.1 | | platform |
| servicios de dominio | ver 3.1 | | services |

- Un solo archivo: `deploy/docker-compose.yml`. `deploy/.env` define `COMPOSE_PROFILES=infra,platform,services,obs` y `ORDERFLOW_PROFILE=local`.
- Todo contenedor tiene healthcheck. Arranque ordenado con `depends_on: condition: service_healthy` (config-server antes que cualquier cliente de config).
- Servicios Java: `mem_limit: 512m`, `-XX:MaxRAMPercentage=75`. Elasticsearch: heap 1g.
- Imagen de cada app: Dockerfile mínimo `eclipse-temurin:21-jre` que copia el jar ya construido por Maven (no se compila dentro de Docker).
- `SPRING_PROFILES_ACTIVE=${ORDERFLOW_PROFILE}` en todas las apps.
- LocalStack necesita el socket de Docker montado (Lambda). Imagen localstack/localstack:4.14.0, fijada porque no requiere token. Los IT con LocalStack usan la misma imagen.
- Eureka: `prefer-ip-address: true` (las réplicas de catalog comparten hostname).

### 3.3 PostgreSQL

Una instancia, una base y un usuario por servicio: `catalog`, `orders`, `inventory`, `credit`, `shipping`, más `keycloak`. Ningún servicio accede a la base de otro.

---

## 4. Seguridad

- Keycloak, realm `orderflow`. `KC_HOSTNAME=http://localhost:8180`, `KC_HTTP_PORT=8180`. Por lo tanto **todo token** (emitido desde el host o desde la red de Docker) tiene `iss = http://localhost:8180/realms/orderflow`.
- Gateway y **cada servicio** validan el JWT: firma con JWKS en `http://keycloak:8180/realms/orderflow/protocol/openid-connect/certs` e `iss` igual al valor anterior. El gateway reenvía el header `Authorization` sin cambios.
- Mapeo de autoridades: `realm_access.roles` → `ROLE_<rol>`; claim `scope` → `SCOPE_<scope>`.
- Clientes:
  - `orderflow-cli`: público, direct access grants habilitado (humanos y E2E). Mapper de atributo de usuario `company_id` → claim `company_id`.
  - `order-service`: confidencial, client credentials, client scope por defecto `catalog.internal`. Secreto en `deploy/.env` (`ORDER_SERVICE_CLIENT_SECRET`), solo local.
- Roles de realm: `buyer`, `ops`.
- Usuarios (contraseña = nombre de usuario, solo local): `buyer-acme` (buyer, company_id=ACME), `buyer-globex` (buyer, GLOBEX), `buyer-initech` (buyer, INITECH), `ops` (ops, sin company_id).
- **Multi-tenant:** el `companyId` de un comprador sale **siempre** del token, nunca del body ni de la query. Un recurso de otra empresa responde **404** (no 403).

| Endpoint | Autorización |
|---|---|
| `POST /api/orders` | ROLE_buyer |
| `GET /api/orders/{id}`, `GET /api/orders` | ROLE_buyer (solo su empresa) u ROLE_ops (todas) |
| `GET /api/catalog/products` | ROLE_buyer u ROLE_ops |
| `POST /api/ops/{service}/dlt/{topic}/replay` | ROLE_ops |
| `GET /internal/prices` (no ruteado por gateway) | SCOPE_catalog.internal |

---

## 5. API pública (vía gateway, `http://localhost:8080`)

### 5.1 Rutas del gateway

| Ruta | Destino |
|---|---|
| `/api/orders/**` | `lb://order-service` (misma ruta) |
| `/api/catalog/**` | `lb://catalog-service` (misma ruta) |
| `/api/ops/{service}/**` | `lb://{service}-service`, reescrita a `/ops/**`; `{service}` ∈ {order, inventory, credit, shipping} |
| `/internal/**` y cualquier otra | 404 |

### 5.2 Pedidos (order-service)

**`POST /api/orders`**
- Header obligatorio `Idempotency-Key` (UUID). Si falta o no es UUID → 400 `MISSING_IDEMPOTENCY_KEY`.
- Body: `{"deliveryDate":"2026-11-20","items":[{"sku":"SKU-0001","quantity":5}]}`
- Validación (400 `VALIDATION_ERROR`): 1–50 ítems; sku no repetido en el pedido; quantity 1–10000; deliveryDate entre mañana y hoy+60 días (zona `America/La_Paz`).
- Precios: llamada síncrona a `GET /internal/prices` (§6). Algún sku desconocido o inactivo → 422 `UNKNOWN_SKU`. Catálogo no disponible o circuito abierto → 503 `CATALOG_UNAVAILABLE` y **la Idempotency-Key no se guarda** (el cliente puede reintentar con la misma).
- En **una sola transacción**: pedido + líneas + saga (`AWAITING_STOCK`) + outbox (`ReserveStock`) + registro de idempotencia.
- Respuesta **202** `{"orderId":"<uuid>","status":"PENDING"}` + header `Location: /api/orders/{orderId}`.

**`GET /api/orders/{orderId}`** → 200:
```json
{"orderId":"…","companyId":"ACME","status":"PENDING","reason":null,"deliveryDate":"2026-11-20",
 "currency":"BOB","total":"450.00","items":[{"sku":"SKU-0001","quantity":5,"unitPrice":"90.00"}],
 "createdAt":"…","updatedAt":"…"}
```
Montos serializados como string con 2 decimales.

**`GET /api/orders?status=&page=0&size=20`** (size ≤ 100) → `{"content":[…],"page":0,"size":20,"totalElements":N}`. Orden: `createdAt` descendente.

**Estado público** (derivado del estado de saga, §8):

| Estado de saga | `status` | `reason` |
|---|---|---|
| AWAITING_STOCK, AWAITING_CREDIT, AWAITING_SHIPMENT, COMPENSATING | PENDING | null |
| CONFIRMED | CONFIRMED | null |
| REJECTED, CANCELLED, COMPENSATION_FAILED | según `failure_reason`: REJECTED si ∈ {OUT_OF_STOCK, CREDIT_LIMIT_EXCEEDED, NO_CREDIT_LINE}; CANCELLED si ∈ {SHIPPING_NO_CAPACITY, STEP_TIMEOUT} | `failure_reason` |

### 5.3 Catálogo (catalog-service)

**`GET /api/catalog/products?page=0&size=20`** → `{"content":[{"sku","name","unitPrice"}],"page","size","totalElements"}`. Solo productos activos. `unitPrice` con el descuento de la empresa del token; para ops, precio base.

### 5.4 Errores

Formato RFC 9457 (`ProblemDetail` de Spring) con propiedad extra `code`.

| code | HTTP |
|---|---|
| VALIDATION_ERROR | 400 |
| MISSING_IDEMPOTENCY_KEY | 400 |
| NOT_FOUND | 404 |
| IDEMPOTENCY_KEY_REUSED | 422 |
| UNKNOWN_SKU | 422 |
| CATALOG_UNAVAILABLE | 503 |
| (sin cuerpo, lo genera el gateway) | 401, 403, 429 |

### 5.5 Operaciones

**`POST /api/ops/{service}/dlt/{topic}/replay?max=N`** (N por defecto 100, máximo 1000) → `{"replayed":n}`. Reinyecta hasta N mensajes de `{topic}.DLT` en `{topic}` conservando los headers `message_id`, `message_type` y `traceparent`. Lo implementa messaging-lib en cada servicio consumidor.

---

## 6. API interna

**`GET /internal/prices?companyId=ACME&skus=SKU-0001,SKU-0002`** (catalog-service) →
```json
{"currency":"BOB","items":[{"sku":"SKU-0001","unitPrice":"9.00"}],"unknownSkus":["SKU-9999"]}
```
`unitPrice = base_price × (1 − discount_pct/100)`, escala 2, HALF_EVEN. Empresa sin descuento registrado → 0 %.
Cliente: order-service con `RestClient` `@LoadBalanced` + token client credentials (`order-service`).

---

## 7. Mensajería Kafka

### 7.1 Broker y topics
- `auto.create.topics.enable=false`. Un contenedor init crea **todos** los topics y sus DLT: 3 particiones, RF 1.
- Key = `orderId` (string UUID) en todos los mensajes. Value = JSON del record de `contracts`.
- Headers obligatorios: `message_id` (UUID = id de la fila de outbox), `message_type` (nombre simple del record), `traceparent` (W3C).
- Consumer group = nombre del servicio (`inventory-service`, etc.).
- Productor: `acks=all`, `enable.idempotence=true`, `max.block.ms=5000`, `request.timeout.ms=5000`, `delivery.timeout.ms=10000`.

| Topic | Productor | Consumidor | Mensajes |
|---|---|---|---|
| `inventory.commands` | order | inventory | ReserveStock, ReleaseStock |
| `inventory.replies` | inventory | order | StockReserved, StockRejected, StockReleased |
| `credit.commands` | order | credit | ReserveCredit, ReleaseCredit |
| `credit.replies` | credit | order | CreditReserved, CreditRejected, CreditReleased |
| `shipping.commands` | order | shipping | ScheduleShipment, CancelShipment |
| `shipping.replies` | shipping | order | ShipmentScheduled, ShipmentRejected, ShipmentCancelled |
| `<cada topic anterior>.DLT` | error handler | replay (§5.5) | copia del mensaje fallido |

### 7.2 Contratos (records en `contracts`)
Tipos: `orderId`, `shipmentId` = UUID; `companyId`, `sku`, `currency` = String; `amount` = BigDecimal; `deliveryDate` = LocalDate.

| Record | Campos |
|---|---|
| ReserveStock | orderId, items: List<Item(sku, quantity)> |
| ReleaseStock | orderId |
| StockReserved | orderId |
| StockRejected | orderId, reason ∈ {OUT_OF_STOCK, ALREADY_RELEASED} |
| StockReleased | orderId |
| ReserveCredit | orderId, companyId, amount, currency |
| ReleaseCredit | orderId |
| CreditReserved | orderId |
| CreditRejected | orderId, reason ∈ {CREDIT_LIMIT_EXCEEDED, NO_CREDIT_LINE, ALREADY_RELEASED} |
| CreditReleased | orderId |
| ScheduleShipment | orderId, companyId, deliveryDate |
| CancelShipment | orderId |
| ShipmentScheduled | orderId, shipmentId |
| ShipmentRejected | orderId, reason ∈ {NO_CAPACITY, ALREADY_CANCELLED} |
| ShipmentCancelled | orderId |
| InvoiceJob (SQS, §11) | orderId, companyId, lines: List<Line(sku, quantity, unitPrice)>, total, currency, confirmedAt (Instant) |
| CatalogImportRow (SQS, §11) | sku, name, basePrice, sourceFile, line |

`contracts` también expone las constantes de nombres de topics y colas.

### 7.3 Errores de consumo
- Reintentables: backoff exponencial 1 s ×2, **3 reintentos (4 intentos en total)**, luego `<topic>.DLT` en la misma partición.
- No reintentables (directo a DLT tras el 1.er intento): JSON inválido, `message_type` desconocido, `NonRetryableMessageException` (de messaging-lib).
- Mientras se reintenta, la partición queda bloqueada (aceptado, §17).

### 7.4 Entrega y duplicados
- Publicación: outbox transaccional + publisher por polling (§10). Entrega at-least-once.
- Consumo: inbox en la misma transacción que el efecto (§10). El efecto se aplica una sola vez por `message_id`.

---

## 8. Saga de pedido (orquestada por order-service)

### 8.1 Estados
`AWAITING_STOCK`, `AWAITING_CREDIT`, `AWAITING_SHIPMENT`, `COMPENSATING`, y finales `CONFIRMED`, `REJECTED`, `CANCELLED`, `COMPENSATION_FAILED`.

`failure_reason` ∈ {OUT_OF_STOCK, CREDIT_LIMIT_EXCEEDED, NO_CREDIT_LINE, SHIPPING_NO_CAPACITY, STEP_TIMEOUT}.

### 8.2 Reglas generales
- Cada evento (respuesta o timeout) se procesa en **una transacción** con **lock de fila** sobre la saga (`SELECT … FOR UPDATE`; el scanner de timeouts usa `FOR UPDATE SKIP LOCKED`). Los comandos que se emiten van al outbox en la misma transacción.
- Al entrar en `AWAITING_*` se fija `step_deadline = now + step-timeout`. Al entrar en `COMPENSATING`, `step_deadline = now + compensation-retry-interval`.
- Al entrar en `COMPENSATING` se marca como pendiente cada liberación emitida (stock / crédito / despacho) y se emiten en paralelo los comandos `Release*`/`CancelShipment`.
- **Todo** comando `Release*`/`Cancel*` es idempotente en el receptor (§9).

### 8.3 Transiciones

| Estado | Evento | Acción | Nuevo estado |
|---|---|---|---|
| (creación) | POST válido | emitir ReserveStock | AWAITING_STOCK |
| AWAITING_STOCK | StockReserved | stock_reserved; emitir ReserveCredit | AWAITING_CREDIT |
| AWAITING_STOCK | StockRejected | reason=OUT_OF_STOCK | REJECTED |
| AWAITING_CREDIT | CreditReserved | credit_reserved; emitir ScheduleShipment | AWAITING_SHIPMENT |
| AWAITING_CREDIT | CreditRejected(r) | reason=r; emitir ReleaseStock | COMPENSATING |
| AWAITING_SHIPMENT | ShipmentScheduled | emitir InvoiceJob (SQS) | CONFIRMED |
| AWAITING_SHIPMENT | ShipmentRejected | reason=SHIPPING_NO_CAPACITY; emitir ReleaseCredit, ReleaseStock | COMPENSATING |
| AWAITING_* | deadline vencido | reason=STEP_TIMEOUT; emitir liberación de todo lo reservado **y** del recurso del paso en curso | COMPENSATING |
| COMPENSATING | StockReleased / CreditReleased / ShipmentCancelled | quitar pendiente; si no quedan → estado final según reason (§5.2) | COMPENSATING o REJECTED/CANCELLED |
| COMPENSATING | deadline vencido, intentos < max | intentos+1; re-emitir pendientes; nuevo deadline | COMPENSATING |
| COMPENSATING | deadline vencido, intentos = max | métrica `orderflow.saga.compensation.failed` +1 | COMPENSATION_FAILED |

### 8.4 Respuestas tardías o inesperadas
- `StockReserved`, `CreditReserved` o `ShipmentScheduled` recibidos en `COMPENSATING` o en un estado final distinto de CONFIRMED → emitir `ReleaseStock` / `ReleaseCredit` / `CancelShipment` respectivamente. En `COMPENSATING` se marca como pendiente; en estado final no cambia el estado.
- `*Released` / `ShipmentCancelled` en estado final → ignorar.
- `*Rejected` en `COMPENSATING` o en estado final → ignorar.
- Cualquier otra combinación no listada → log WARN + métrica `orderflow.saga.unexpected.reply` +1, ignorar. **No** lanzar excepción (no va a DLT).

---

## 9. Reglas de dominio

Dinero: BigDecimal escala 2, HALF_EVEN, moneda única `BOB`. Total del pedido = Σ quantity × unitPrice.

**catalog-service.** Producto (sku `^[A-Z0-9-]{3,32}$`, nombre 1–200 sin comas, base_price > 0 con ≤ 2 decimales, activo). Descuento por empresa 0–50 %.

**inventory-service.** Stock disponible por sku; reserva por orderId con estado RESERVED o RELEASED.
- ReserveStock: si ya existe reserva para el orderId → RESERVED responde StockReserved; RELEASED responde StockRejected(ALREADY_RELEASED). Si no existe: descuenta **todos** los ítems de forma atómica (`UPDATE … SET available = available - q WHERE sku = ? AND available >= q`); si algún ítem no alcanza o no tiene fila → rollback total y StockRejected(OUT_OF_STOCK).
- ReleaseStock: RESERVED → devuelve stock, pasa a RELEASED, StockReleased. RELEASED → StockReleased. Inexistente → crea la reserva en RELEASED (lápida) y StockReleased.

**credit-service.** Línea por empresa (`credit_limit`, `used`); reserva por orderId RESERVED o RELEASED. Mismas reglas de idempotencia y lápida que inventory. Reserva atómica: `UPDATE … SET used = used + a WHERE company_id = ? AND used + a <= credit_limit`. Sin línea → CreditRejected(NO_CREDIT_LINE). Excede → CreditRejected(CREDIT_LIMIT_EXCEEDED). Ya liberada → CreditRejected(ALREADY_RELEASED).

**shipping-service.** Capacidad por fecha (`max_orders`, `booked`); despacho por orderId SCHEDULED o CANCELLED. Agendar: `UPDATE … SET booked = booked + 1 WHERE delivery_date = ? AND booked < max_orders`; sin fila o sin cupo → ShipmentRejected(NO_CAPACITY). CancelShipment: SCHEDULED → libera cupo, CANCELLED, ShipmentCancelled; CANCELLED → ShipmentCancelled; inexistente → lápida CANCELLED y ShipmentCancelled. ScheduleShipment sobre lápida → ShipmentRejected(ALREADY_CANCELLED).

---

## 10. Idempotencia y entrega

| Punto | Mecanismo |
|---|---|
| `POST /api/orders` | Tabla en `orders` con PK (companyId, key), hash SHA-256 del body normalizado, status y body de la respuesta. Misma key y mismo hash → misma respuesta. Misma key con otro hash → 422 IDEMPOTENCY_KEY_REUSED. Dos requests concurrentes con la misma key: la segunda espera el conflicto de PK y devuelve la respuesta guardada. Retención 24 h. |
| Publicación | Outbox en la base del servicio, en la misma transacción que el cambio. Publisher por polling con `FOR UPDATE SKIP LOCKED`, destinos KAFKA o SQS. El id de la fila es el `message_id`. Retención de publicados 7 días. |
| Consumo Kafka | Inbox (message_id, consumer) insertado en la misma transacción que el efecto. Duplicado → se ignora sin responder. Retención 7 días. |
| SQS invoice-jobs | Clave S3 determinista (§11): reprocesar sobrescribe el mismo objeto. |
| SQS catalog-import | Upsert por sku. |

---

## 11. AWS (LocalStack, región `us-east-1`, credenciales `test`/`test`)

| Recurso | Configuración |
|---|---|
| Bucket `orderflow-catalog-imports` | Prefijos `incoming/` (dispara catalog-import-fn en objetos `.csv`) y `errors/` |
| Bucket `orderflow-invoices` | Objeto `invoices/{companyId}/{orderId}.pdf`, content-type `application/pdf` |
| Cola `catalog-import` | visibility 30 s, redrive a `catalog-import-dlq` con maxReceiveCount 3. Consumidor: catalog-service |
| Cola `invoice-jobs` | visibility 30 s, redrive a `invoice-jobs-dlq` con maxReceiveCount 3. Consumidor: invoice-fn (batch 5, ReportBatchItemFailures). Productor: order-service vía outbox |
| Lambda `catalog-import-fn` | Java 21, 512 MB, timeout 60 s |
| Lambda `invoice-fn` | Java 21, 512 MB, timeout 20 s |

- Atributos de mensaje SQS: `message_id`, `message_type`, `traceparent`.
- Las Lambdas leen el endpoint de `AWS_ENDPOINT_URL` (definido en el script de init de LocalStack).
- **CSV de catálogo:** UTF-8, separador coma, sin comillas, encabezado exacto `sku,name,basePrice`, máximo 10 000 filas de datos.
  - Encabezado distinto → archivo rechazado entero: archivo de errores con línea 1, `INVALID_HEADER`.
  - Más de 10 000 filas → rechazado entero: línea 0, `TOO_MANY_ROWS`.
  - Por fila: `INVALID_SKU`, `INVALID_NAME`, `INVALID_PRICE` (reglas de §9), `DUPLICATE_SKU_IN_FILE` (la 2.ª aparición y siguientes).
  - Las filas válidas van a la cola `catalog-import` como CatalogImportRow. El `message_id` es un UUID determinista (nameUUIDFromBytes de `key + etag + línea`).
  - Si hubo errores: `errors/{nombreOriginal}.errors.csv` con encabezado `line,reason` (la línea 1 es el encabezado del CSV original). Sin errores → no se escribe archivo.
- Las Lambdas escriben logs JSON con `traceId` (extraído de `traceparent`) a stdout. No se envían a ELK (§17).

---

## 12. Resiliencia, timeouts y límites

| Punto | Valor |
|---|---|
| order→catalog HTTP | connect 1 s, read 2 s |
| Circuit breaker `catalog` (Resilience4j) | ventana 20 llamadas, mínimo 10, umbral de fallo 50 %, abierto 30 s, half-open 3 llamadas. **Sin retry.** Fallback = 503 CATALOG_UNAVAILABLE |
| Gateway response timeout | 5 s |
| Rate limit (gateway, Redis) | por claim `company_id` (si no existe, por `sub`): replenish 20/s, burst 40, para todo `/api/**` |
| Hikari | connection-timeout 2 s |
| Kafka productor | ver §7.1 |

---

## 13. Observabilidad

- **Logs:** JSON (logstash encoder) a stdout. Campos MDC: `traceId`, `spanId`, `companyId`, `orderId`, `messageId` cuando aplican. Nunca tokens ni payloads completos. Filebeat (autodiscover de Docker) → Elasticsearch, índice `orderflow-logs-*` → Kibana.
- **Métricas:** `/actuator/prometheus`. Prometheus descubre targets con `eureka_sd_configs` (scrape 15 s, evaluación de reglas 15 s).

| Métrica (Micrometer) | Tipo | Dónde |
|---|---|---|
| `orderflow.outbox.pending` | gauge | todo servicio con outbox |
| `orderflow.saga.finished` (tag `state`) | counter | order |
| `orderflow.saga.compensation.failed` | counter | order |
| `orderflow.saga.unexpected.reply` | counter | order |
| `orderflow.sqs.dlq.messages` (tag `queue`) | gauge, sondeo cada `orderflow.dlq-monitor.interval` | order (`invoice-jobs-dlq`), catalog (`catalog-import-dlq`) |

- **Alertas** (reglas de Prometheus; "alerta" = estado FIRING en Prometheus, sin Alertmanager):

| Alerta | Expresión | for |
|---|---|---|
| InvoiceDlqNotEmpty | `orderflow_sqs_dlq_messages{queue="invoice-jobs-dlq"} > 0` | 0m |
| CatalogImportDlqNotEmpty | `orderflow_sqs_dlq_messages{queue="catalog-import-dlq"} > 0` | 0m |
| SagaCompensationFailed | `increase(orderflow_saga_compensation_failed_total[5m]) > 0` | 0m |
| OutboxBacklog | `orderflow_outbox_pending > 1000` | 1m |
| CatalogCircuitOpen | `resilience4j_circuitbreaker_state{name="catalog",state="open"} == 1` | 0m |

- **Tracing:** Micrometer Tracing (bridge OTel) → OTLP HTTP `http://jaeger:4318/v1/traces`, muestreo 100 %. Observación habilitada en KafkaTemplate y listeners. El publisher del outbox reabre el contexto guardado en la fila (`traceparent`), de modo que gateway → order → inventory/credit/shipping forman **una sola traza**. El span de creación de pedido lleva el atributo `order.id`. Las Lambdas solo correlacionan por `traceId` en logs.
- **Grafana:** datasource Prometheus provisionado y un dashboard "OrderFlow" con: sagas por estado final, outbox pending, estado del circuito, mensajes en DLQ, latencia p95 HTTP por servicio.

---

## 14. Entornos y tests

| Perfil | Uso |
|---|---|
| `local` | stack de desarrollo |
| `e2e` | stack para la suite E2E (timeouts cortos, §15) |

| Tipo | Sufijo | Alcance | Ejecución |
|---|---|---|---|
| Unitario | `*Test.java` | sin contexto Spring ni contenedores | `./mvnw verify` (surefire) |
| Integración | `*IT.java` | `@SpringBootTest` + Testcontainers (`@ServiceConnection`); dependencias HTTP externas con WireMock. Sin slices (`@DataJpaTest`, `@WebMvcTest`, …) | `./mvnw verify` (failsafe) |
| E2E | `*E2E.java` en `e2e-tests` | contra el stack de compose levantado con `ORDERFLOW_PROFILE=e2e` | `./mvnw -pl e2e-tests verify -Pe2e` |

- Inyección de fallos E2E: `docker compose pause|unpause|stop|start` sobre contenedores. **No** se usa Toxiproxy (con Eureka el tráfico no pasaría por el proxy).
- E2E corre secuencial. Puede preparar datos vía JDBC (Postgres expuesto); las aserciones de resultado se hacen vía API y deltas de datos.

---

## 15. Configuración

- config-server con backend **native** sobre la carpeta `config-repo/` (montada en el contenedor). Archivos: `application.yml`, `application-local.yml`, `application-e2e.yml`, `<servicio>.yml`.
- Clientes: `spring.config.import=configserver:` con `fail-fast: true`; el orden de arranque lo garantiza compose (§3.2). En tests: config y Eureka deshabilitados.
- Secretos: solo variables de entorno (`deploy/.env`). Nunca en `config-repo/`.

| Clave | local | e2e |
|---|---|---|
| `orderflow.outbox.poll-interval` | PT0.2S | PT0.2S |
| `orderflow.outbox.batch-size` | 100 | 100 |
| `orderflow.outbox.retention` | P7D | P7D |
| `orderflow.inbox.retention` | P7D | P7D |
| `orderflow.saga.step-timeout` | PT5M | PT20S |
| `orderflow.saga.compensation-retry-interval` | PT1M | PT10S |
| `orderflow.saga.max-compensation-attempts` | 5 | 5 |
| `orderflow.saga.timeout-check-interval` | PT1S | PT1S |
| `orderflow.idempotency.retention` | PT24H | PT24H |
| `orderflow.dlq-monitor.interval` | PT30S | PT30S |

Los IT fijan sus propios valores cortos con `@SpringBootTest(properties=…)`.

---

## 16. Datos semilla

Migraciones Flyway `R__seed.sql` en `db/seed/`, ubicación activa solo en los perfiles `local` y `e2e`.

| Servicio | Datos |
|---|---|
| catalog | SKU-0001 … SKU-0020; nombre "Producto 00NN"; base_price = NN × 10.00; activos. Descuentos: ACME 10 %, GLOBEX 0 %, INITECH 5 % |
| inventory | 1000 unidades de cada SKU-0001 … SKU-0020 |
| credit | ACME límite 1 000 000.00; GLOBEX límite 500.00; INITECH sin línea |
| shipping | `max_orders = 100` para cada fecha desde `current_date` hasta `current_date + 90` (insert si no existe) |
| Keycloak | `deploy/keycloak/realm-orderflow.json` (§4) |

---

## 17. Limitaciones aceptadas (no se resuelven en v1)

1. Con más de una instancia del publisher, el orden por key en el outbox no está garantizado. La saga no depende de ese orden (lápidas §9 y respuestas tardías §8.4).
2. Los reintentos de consumo bloquean la partición durante el backoff (~7 s).
3. Importaciones de catálogo concurrentes pueden pisarse (último upsert gana).
4. Logs de Lambda fuera de ELK; trazas de Lambda correlacionadas solo por `traceId` en logs.
5. Kafka con un broker y RF 1; Keycloak en modo dev. Solo apto para local.
