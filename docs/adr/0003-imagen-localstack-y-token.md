# 0003 — Imagen de LocalStack y requisito de token

- **Estado:** Aceptado (imagen anterior fijada sin token, con el tag `4.14.0` probado en el spike; decisión del humano condicionada al spike, 2026-11-06). La instrucción decía "opción 1", pero fijar el tag probado corresponde a la opción 2 de este documento.
- **Fecha:** 2026-11-06
- **Fase / ítem donde surgió:** Fase 0, "LocalStack: script de init con buckets, colas y DLQs" y `SPEC §3.2`: "Verificar en Fase 0 el requisito vigente de la imagen de LocalStack (licencia/token)"

## Problema
La verificación que pide `SPEC §3.2` da un cambio de condiciones. Según el blog oficial de LocalStack (5 de marzo de 2026): "Starting March 23, 2026, LocalStack for AWS will ship as a single, unified container image requiring an auth token".

- SPEC no define ningún token ni fija un tag de imagen. El último tag publicado es `2026.10.6`.
- El plan gratuito (Hobby) se describe como "For hobbyists & other non-commercial usage". No se pudo confirmar en la página de precios que incluya S3, SQS y Lambda, porque la tabla por plan no se pudo leer.
- El blog ofrece como alternativa fijar una imagen anterior ("for example, `4.12`"), a cambio de no recibir actualizaciones. Los tags `4.12`… no se probaron: Docker no estaba corriendo. `4.13`, `4.14` y `4.14.0` existen en Docker Hub. Un artículo de terceros dice que 4.14.x es la última sin token, sin confirmación oficial.

Afecta a la Fase 0, a la Fase 4 (Lambdas) y a F00, F14 y F15, y también a cualquier IT con el contenedor LocalStack de Testcontainers.

## Opciones (máximo 2)
1. **Imagen actual con token:** `localstack/localstack:2026.10.6` y `LOCALSTACK_AUTH_TOKEN` en el entorno. Requiere crear una cuenta, sirve solo para uso no comercial en el plan gratuito, y cada IT y cada máquina necesitan el token. Hay que confirmar antes que Hobby cubre Lambda.
2. **Imagen anterior fijada sin token:** `localstack/localstack:4.12`, el tag que cita el blog oficial. Sin cuenta ni secreto y reproducible, pero sin actualizaciones. Aceptable porque LocalStack solo se usa en local (`SPEC §17`, punto 5). Hay que confirmar al levantar Docker que arranca sin token y que ejecuta Lambda Java 21.

## Recomendación
Opción 2. El stack es solo local, y no depender de una cuenta ni de un secreto externo mantiene los F reproducibles en cualquier máquina. Si la prueba de arranque falla, se pasa a la opción 1. Si el proyecto tiene uso comercial, corresponde la opción 1 con un plan de pago.

## Resultado del spike (2026-11-06)
Orden pedido: 4.14 → 4.13 → 4.12, sin `LOCALSTACK_AUTH_TOKEN`, con el socket de Docker montado y en una red propia (`LAMBDA_DOCKER_NETWORK`), como en compose.

| Prueba con `4.14` | Resultado |
|---|---|
| Arranque sin token | OK: `"version":"4.14.0:3d5a0c70e"`, `"edition":"community"`, `"is_license_activated":false` |
| Lambda `java21` creada e invocada | OK: HTTP 200, respuesta `{"pong":true,"java":"21.0.13"}` |
| SQS→Lambda | OK: event source mapping con batch 5 y `ReportBatchItemFailures`, mensaje en los logs de la Lambda, cola en 0 |

- `4.14` y `4.14.0` tienen el mismo digest (`sha256:3ebc37595918b8accb852f8048fef2aff047d465167edd655528065b07bc364a`). Se fija `4.14.0` porque es el tag exacto.
- 4.13 y 4.12 no hicieron falta como respaldo. En una primera corrida también arrancaron sin token y pasaron SQS→Lambda; su invocación directa no quedó probada por un error del script (flag de AWS CLI v2 en una imagen con CLI v1).
- **No probado:** disparo S3→Lambda, reintentos con `batchItemFailures` no vacío, redrive a DLQ, y el contenedor LocalStack de Testcontainers. Se ejercitan en la Fase 4 (F14, F15).

## Cambios exactos aplicados
En `docs/SPEC.md` §3.2, se reemplazó "Verificar en Fase 0 el requisito vigente de la imagen de LocalStack (licencia/token)." por:

`Imagen localstack/localstack:4.14.0, fijada porque no requiere token. Los IT con LocalStack usan la misma imagen.`

No cambian FAILURES, PHASES ni las skills.

---
Reglas: un ADR solo **corrige** una contradicción o **elimina** algo. No agrega alcance; eso va a BACKLOG.md.
Lo acepta el humano, que aplica los cambios a los archivos protegidos y cambia el estado.
