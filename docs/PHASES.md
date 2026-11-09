# OrderFlow — Fases

> Fase actual: 3

Reglas: una fase empieza solo cuando la anterior está toda marcada y `./mvnw -q verify` está verde. Claude marca checkboxes y actualiza la línea "Fase actual"; no agrega ni quita ítems (eso requiere ADR).

## Fase 0 — Fundaciones
- [x] Pom raíz multi-módulo + wrapper; versiones fijadas según SPEC §2; surefire (`*Test`) y failsafe (`*IT`); perfil `e2e` (`*E2E`)
- [x] Módulo `contracts`: todos los records y constantes de SPEC §7.2 y §11
- [x] `deploy/docker-compose.yml` + `deploy/.env` con perfiles, healthchecks, `depends_on` y límites de memoria (SPEC §3.2)
- [x] Postgres: script de init con bases y usuarios (SPEC §3.3)
- [x] Kafka: contenedor init que crea todos los topics y DLT (SPEC §7.1)
- [x] LocalStack: script de init con buckets, colas y DLQs (SPEC §11). Las Lambdas se agregan en Fase 4
- [x] Keycloak: `deploy/keycloak/realm-orderflow.json` (SPEC §4)
- [x] `config-server` + `config-repo/` con las claves de SPEC §15
- [x] `discovery-server`
- [x] `gateway`: rutas (SPEC §5.1), validación JWT (SPEC §4), rate limit (SPEC §12)
- [x] Observabilidad: Prometheus (eureka_sd), Grafana (datasource), Elasticsearch + Kibana + Filebeat, Jaeger
- [x] Criterio de salida: todo healthy; `GET /api/orders` sin token → 401; token de `buyer-acme` obtenido por password grant con `orderflow-cli`; UIs de Eureka, Kibana, Grafana, Prometheus y Jaeger accesibles

## Fase 1 — messaging-lib y catálogo
- [x] messaging-lib: outbox (destinos KAFKA y SQS), publisher, métrica `orderflow.outbox.pending`, limpieza (skill `outbox`)
- [x] F09
- [x] messaging-lib: inbox, error handler + DLT, endpoint de replay (skill `kafka-consumer`)
- [x] catalog-service: productos, descuentos, `GET /api/catalog/products`, `GET /internal/prices`, seeds; 2 réplicas en compose

## Fase 2 — API de pedidos
- [x] order-service: `POST /api/orders` (validación, precios vía catalog con LB + circuit breaker + client credentials, idempotencia, saga en AWAITING_STOCK, outbox ReserveStock), `GET` por id y listado, multi-tenant
- [x] Módulo `e2e-tests`: base de RestAssured, tokens, JDBC, helper de `docker compose`
- [x] F01
- [x] F02
- [x] F16
- [x] F12
- [x] F17

## Fase 3 — Saga completa
- [x] inventory-service (SPEC §9)
- [x] credit-service (SPEC §9)
- [x] shipping-service (SPEC §9)
- [x] order-service: orquestador con todas las transiciones de SPEC §8 y scanner de timeouts
- [ ] F06
- [ ] F13
- [ ] F10
- [ ] F03
- [ ] F04
- [ ] F05
- [ ] F07
- [ ] F08
- [ ] F11
- [ ] F19

## Fase 4 — AWS
- [ ] invoice-fn + InvoiceJob vía outbox SQS desde order-service
- [ ] catalog-import-fn + consumidor SQS en catalog-service
- [ ] Lambdas desplegadas por el init de LocalStack
- [ ] Monitores de DLQ (`orderflow.sqs.dlq.messages`) y todas las reglas de alerta de SPEC §13
- [ ] F00
- [ ] F14
- [ ] F15

## Fase 5 — Observabilidad y cierre
- [ ] Atributo `order.id` en el span de creación y continuidad de traza a través del outbox
- [ ] F18
- [ ] Dashboard "OrderFlow" en Grafana (SPEC §13)
- [ ] Suite completa (`./mvnw verify` + E2E) verde dos veces seguidas
