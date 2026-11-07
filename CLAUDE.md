# OrderFlow — instrucciones para Claude Code

Sistema de pedidos mayoristas B2B: microservicios Spring Boot, Kafka y AWS (LocalStack). El objetivo es robustez ante fallos, demostrada por tests.

## Una sola fuente por tema

| Tema | Fuente única | ¿Claude puede editarla? |
|---|---|---|
| Qué se construye: alcance, contratos, APIs, topics, colas, estados, valores de config, dependencias | `docs/SPEC.md` | No (hook) |
| Definición de terminado: escenarios de fallo | `docs/FAILURES.md` | No (hook) |
| Cómo implementar cada patrón | `.claude/skills/*/SKILL.md` | No (hook) |
| Plan y avance | `docs/PHASES.md` | Solo marcar checkboxes y la línea "Fase actual" |
| Propuestas de cambio al diseño | `docs/adr/` | Solo crear ADR en estado `Propuesto` |
| Ideas fuera de alcance | `docs/BACKLOG.md` | Solo agregar líneas |

Cuando exista el módulo `contracts`, su código debe coincidir con SPEC §7. Si no coincide, gana SPEC y se corrige el código.

## Reglas

1. **No agregues** componentes, servicios, topics, colas, endpoints, tablas públicas, propiedades `orderflow.*` ni dependencias que no estén en SPEC. Dependencias permitidas: SPEC §2.
2. **Contradicción, hueco o imposibilidad** que afecte a un contrato, a otro módulo o a un F: **detente**. Crea `docs/adr/NNNN-titulo.md` en estado `Propuesto` (plantilla `0000-template.md`, máximo 2 opciones) y avisa. No elijas tú ni lo resuelvas por otra vía.
3. **Detalles internos** de un módulo que SPEC no define (clases, paquetes internos, queries, nombres de tablas no públicas): elige lo más simple y sigue. No lo documentes.
4. **Ideas o mejoras:** una línea en `docs/BACKLOG.md` y sigue con la tarea.
5. Trabaja **solo en la fase actual** de `docs/PHASES.md`. No adelantes trabajo.
6. **Un test de FAILURES nunca se ajusta para que pase.** Se arregla el código. Si el test parece incorrecto, aplica la regla 2.
7. No desactives hooks ni busques otra forma de editar archivos protegidos.

## Flujo por ítem de PHASES

1. Lee el ítem y, si es un F, su fila en `docs/FAILURES.md` y las secciones de SPEC que cita.
2. Carga la skill que corresponde (tabla abajo).
3. Si es un F: escribe primero el test (skill `failure-test`) y confirma que falla por la razón esperada.
4. Implementa lo mínimo para que pase.
5. Ejecuta `./mvnw -q verify` (y la suite E2E si el ítem es E2E). Todo verde.
6. Marca el checkbox. Un commit por ítem: `feat(<modulo>): F07 timeout de saga` o `chore(<modulo>): <resumen>`.

| Trabajo | Skill |
|---|---|
| Crear un módulo de servicio o app | `new-service` |
| Escribir en otro sistema (Kafka o SQS) desde una transacción | `outbox` |
| Consumir de Kafka | `kafka-consumer` |
| Cualquier cambio en la saga de order-service | `saga-step` |
| Escribir un test de FAILURES | `failure-test` |
| Lambdas y consumo/producción SQS | `aws-lambda` |

## Comandos

```bash
./mvnw -q verify                                   # unit + IT de todos los módulos
./mvnw -q -pl <modulo> -am verify                  # un módulo
./mvnw -q package -DskipTests                      # jars para las imágenes
docker compose -f deploy/docker-compose.yml up -d --build             # stack local
ORDERFLOW_PROFILE=e2e docker compose -f deploy/docker-compose.yml up -d --build   # stack para E2E
./mvnw -q -pl e2e-tests verify -Pe2e               # suite E2E (stack e2e levantado)
docker compose -f deploy/docker-compose.yml down -v
```

## Tests (el hook `check-test-integrity` rechaza las violaciones)

- `*Test.java`: unitario. Sin contexto Spring ni contenedores. Mockito permitido.
- `*IT.java`: `@SpringBootTest` + Testcontainers reales (Postgres, Kafka, LocalStack) con `@ServiceConnection`. WireMock solo para HTTP externo (catalog y token endpoint desde order). Sin slices (`@DataJpaTest`, `@WebMvcTest`…), sin `@EmbeddedKafka`, sin mockear `KafkaTemplate`, sin H2.
- `*E2E.java`: módulo `e2e-tests`, contra el stack de compose.
- En todos: prohibido `@Disabled`, `Assumptions`/`assumeTrue` y `Thread.sleep` (usa Awaitility).

## Convenciones

- Java 21, records para DTOs y mensajes, inyección por constructor, sin Lombok.
- Paquete base `com.orderflow.<modulo>`.
- Dinero: `BigDecimal`, escala 2, `RoundingMode.HALF_EVEN`.
- Errores HTTP: `ProblemDetail` con propiedad `code` (SPEC §5.4).
- Logs: nunca tokens ni payloads completos. Sí `orderId`, `companyId`, `messageId` en MDC.
