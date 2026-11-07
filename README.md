# OrderFlow

Pedidos mayoristas B2B con microservicios Spring Boot, Kafka y AWS (LocalStack), construido con Claude Code.

## Requisitos de la máquina
- Java 21, Docker con Compose v2, `jq` (lo usan los hooks; sin él los hooks bloquean).
- RAM: 16 GB mínimo, 32 GB recomendado (unos 20 contenedores).

## Mapa
| Archivo | Para qué |
|---|---|
| `CLAUDE.md` | Reglas y flujo de trabajo de Claude |
| `docs/SPEC.md` | Diseño congelado (qué) |
| `docs/FAILURES.md` | Escenarios de fallo = definición de terminado |
| `docs/PHASES.md` | Plan y avance |
| `docs/adr/` | Cambios al diseño |
| `docs/BACKLOG.md` | Ideas fuera de alcance |
| `.claude/skills/` | Cómo implementar cada patrón |
| `.claude/hooks/` | Protección de archivos congelados e integridad de tests |

## Cómo trabajar con Claude Code
1. `git init && git add . && git commit -m "chore: estructura inicial"`
2. `chmod +x .claude/hooks/*.sh`
3. `claude` en la raíz. Primer prompt: `Empieza la fase actual.`
4. Una fase por sesión (`/clear` entre fases). Revisa los diffs de tests en cada commit.

## Cambiar el diseño
Claude no puede editar `docs/SPEC.md`, `docs/FAILURES.md` ni `.claude/`. Si propone un ADR:
- Lo aceptas → editas tú los archivos (los hooks no te afectan) y marcas el ADR como Aceptado.
- O habilitas temporalmente la edición: `ORDERFLOW_UNFREEZE=1 claude`.
