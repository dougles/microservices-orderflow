#!/usr/bin/env bash
# PreToolUse: bloquea cambios a archivos congelados (docs/SPEC.md, docs/FAILURES.md, .claude/).
# Salida 2 = bloquear; stderr se muestra a Claude.
# Para habilitar edición temporal: ORDERFLOW_UNFREEZE=1 claude
set -uo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "Hook guard-protected: falta 'jq'. Pide al humano que lo instale." >&2
  exit 2
fi
[ "${ORDERFLOW_UNFREEZE:-0}" = "1" ] && exit 0

input="$(cat)"
tool="$(jq -r '.tool_name // empty' <<<"$input")"
root="${CLAUDE_PROJECT_DIR:-$(pwd)}"

PROTECTED_PATH_RE='(^|/)(docs/SPEC\.md|docs/FAILURES\.md|\.claude/)'
PROTECTED_IN_CMD_RE='(SPEC\.md|FAILURES\.md|\.claude/)'
WRITE_CMD_RE='(sed[[:space:]]+-[a-zA-Z]*i|perl[[:space:]]+-[a-zA-Z]*i|>|tee([[:space:]]|$)|mv[[:space:]]|rm[[:space:]]|cp[[:space:]]|truncate|chmod|ln[[:space:]]|git[[:space:]]+(checkout|restore|rm|mv|reset|apply))'

block() {
  cat >&2 <<EOF
BLOQUEADO: '$1' toca un archivo congelado (docs/SPEC.md, docs/FAILURES.md o .claude/).
- Para leerlo usa las herramientas Read/Grep, no comandos con redirección.
- Si encontraste una contradicción o un hueco: detente, crea docs/adr/NNNN-<titulo>.md en estado "Propuesto" (plantilla docs/adr/0000-template.md) y avisa al humano.
- No busques otra forma de modificarlo.
EOF
  exit 2
}

case "$tool" in
  Edit|Write|MultiEdit|NotebookEdit)
    path="$(jq -r '.tool_input.file_path // .tool_input.notebook_path // empty' <<<"$input")"
    rel="${path#"$root"/}"
    if [[ "$rel" =~ $PROTECTED_PATH_RE ]]; then
      block "$rel"
    fi
    ;;
  Bash)
    cmd="$(jq -r '.tool_input.command // empty' <<<"$input")"
    if [[ "$cmd" =~ $PROTECTED_IN_CMD_RE ]] && [[ "$cmd" =~ $WRITE_CMD_RE ]]; then
      block "$(printf '%s' "$cmd" | head -c 160)"
    fi
    ;;
esac
exit 0
