# 0001 — Diseño v1 congelado

- **Estado:** Aceptado
- **Fecha:** 2026-11-06

## Decisión
`docs/SPEC.md` y `docs/FAILURES.md` quedan congelados como v1. El proyecto termina cuando F00–F19 pasan. No se agrega alcance hasta entonces.

## Ajustes respecto del borrador conversado (ya reflejados en SPEC)
- Sin Toxiproxy: con Eureka el tráfico no pasa por un proxy. Los fallos se inyectan con `docker compose pause/stop`.
- Config Server con backend native (`config-repo/`), sin repo Git separado.
- Compensaciones en paralelo; la saga espera todas las confirmaciones.
- Lápidas en las liberaciones (inventory, credit, shipping): un `Release` que llega antes que su `Reserve` impide la reserva posterior.
- Se agregó F19 (compensación sin respuesta) para que `COMPENSATING` tenga una salida definida.
- Sin SNS ni topic `order.events`: no hay consumidores.
- Lambdas sin tracing OTel; se correlacionan por `traceId` en logs.
- Cualquier fallo en Lambda, cola o publicación tiene un destino explícito (DLQ/DLT + alerta).
