#!/usr/bin/env bash
# SessionStart: inyecta en el contexto la fase actual y sus pendientes.
set -uo pipefail
root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
phases="$root/docs/PHASES.md"

if [ ! -f "$phases" ]; then
  echo "OrderFlow: no se encontró docs/PHASES.md."
  exit 0
fi

current="$(grep -m1 -E '^> Fase actual:' "$phases" | sed -E 's/^> Fase actual:[[:space:]]*//')"
echo "OrderFlow — fase actual: ${current:-desconocida}"
echo "Pendientes de la fase:"
awk -v n="$current" '
  $0 ~ "^## Fase " n "( |$)" {inside=1; next}
  /^## Fase / {inside=0}
  inside && /^- \[ \]/ {print "  " $0}
' "$phases"
echo "Recordatorio: SPEC, FAILURES y .claude/ están congelados. Ante una contradicción o un hueco: ADR y detenerse (CLAUDE.md, regla 2)."
exit 0
