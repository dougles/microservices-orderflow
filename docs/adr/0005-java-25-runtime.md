# 0005 — Actualizar el runtime de aplicación a Java 25

- **Estado:** Propuesto
- **Fecha:** 2026-11-07
- **Fase / ítem donde surgió:** Fase 2; solicitud de modernización de runtime fuera de los ítems planificados

## Problema
La solicitud de actualizar el runtime de aplicación a Java 25 contradice el diseño congelado: `docs/SPEC.md` §2 fija Java 21 y §3.2 requiere `eclipse-temurin:21-jre`; `README.md` y `CLAUDE.md` también declaran Java 21. Además, `.claude/skills/new-service/SKILL.md` prescribe Java 21 para nuevos servicios. ADR 0001 congela SPEC y FAILURES hasta terminar F00–F19; `docs/PHASES.md` indica que el proyecto sigue en Fase 2 y no se debe adelantar trabajo. El SPEC y los archivos `.claude/` están protegidos y no pueden editarse sin aceptación humana.

No es posible actualizar y verificar Java 25 de forma coherente respetando las fuentes de verdad actuales, porque las instrucciones de compilación, las imágenes de contenedor y las guías de implementación seguirían exigiendo Java 21.

## Opciones (máximo 2)
1. **Diferir:** mantener Java 21 hasta completar el diseño v1 (F00–F19); luego proponer la actualización por ADR.
2. **Autorizar la actualización ahora:** aceptar una revisión coordinada del diseño y del orden de fases para permitir Java 25 en la aplicación, Maven y sus imágenes de ejecución. Mantener Java 21 para las Lambdas únicamente si se confirma expresamente que no forman parte del objetivo; de otro modo, incluir su runtime y despliegue en la misma revisión tras comprobar soporte del proveedor.

## Recomendación
Opción 1 para preservar el alcance y la regla de congelamiento de ADR 0001 durante la Fase 2. La solicitud explícita de Java 25 requiere que el responsable del diseño acepte la opción 2 y actualice los archivos protegidos antes de ejecutar la modernización.

## Cambios exactos si se acepta
- `docs/SPEC.md` §2: cambiar el runtime de Java 21 a Java 25 y añadir los requisitos mínimos de Maven/JDK para compilar.
- `docs/SPEC.md` §3.2: cambiar la imagen de aplicación a `eclipse-temurin:25-jre`.
- `README.md` y `CLAUDE.md`: actualizar los requisitos y convenciones de Java a 25.
- `.claude/skills/new-service/SKILL.md`: cambiar la imagen base de ejemplo a `eclipse-temurin:25-jre`.
- `pom.xml`: establecer `<java.version>25</java.version>` y validar los plugins Maven y dependencias del reactor con JDK 25; mantener el wrapper en una versión compatible.
- Los cinco Dockerfiles de servicios existentes: cambiar `eclipse-temurin:21-jre` por `eclipse-temurin:25-jre`.
- Configuración de CI: actualizar las versiones de JDK a 25 si existe o se incorpora un flujo CI autorizado; actualmente no se encontró configuración CI en el repositorio.
- Si la opción elegida incluye Lambdas: `docs/SPEC.md` §2 y §11, `.claude/skills/aws-lambda/SKILL.md` y `deploy/localstack/init/ready.d/10-resources.sh`: revisar y actualizar el runtime de Lambda solo después de confirmar que Java 25 está soportado por AWS Lambda y por la versión LocalStack fijada.

## Variante elegida (2026-11-12)
El humano eligió la opción 2 sin las Lambdas, con el diseño v1 ya terminado (Fase 5 completa, F00–F19 en verde). Las Lambdas siguen en `java21`. Como ambas dependen de `contracts`, ese módulo también se compila para Java 21: si se compilara para 25, el runtime `java21` no podría cargar sus clases.

Ya aplicado en la rama `java-25` (archivos no protegidos):
- `pom.xml`: `<java.version>25</java.version>`.
- `contracts/pom.xml`, `invoice-fn/pom.xml` y `catalog-import-fn/pom.xml`: `<java.version>21</java.version>`.
- Los ocho Dockerfiles de apps: `eclipse-temurin:25-jre`.
- `README.md` y `CLAUDE.md`: Java 25, con la excepción de Lambdas y `contracts`.

Texto exacto para los archivos protegidos (lo aplica el humano):
- `docs/SPEC.md` §2, línea "- Java 21. Maven multi-módulo…" → `- Java 25 para las apps; las Lambdas (§11) y \`contracts\` compilan para Java 21. Maven multi-módulo con wrapper (\`./mvnw\`). Sin Lombok.`
- `docs/SPEC.md` §3.2: `eclipse-temurin:21-jre` → `eclipse-temurin:25-jre`.
- `docs/SPEC.md` §11 y `.claude/skills/aws-lambda/SKILL.md`: sin cambios.
- `.claude/skills/new-service/SKILL.md`: `FROM eclipse-temurin:21-jre` → `FROM eclipse-temurin:25-jre`.

---
Reglas: un ADR solo **corrige** una contradicción o **elimina** algo. No agrega alcance; eso va a BACKLOG.md.
