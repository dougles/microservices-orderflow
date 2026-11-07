#!/usr/bin/env bash
# PostToolUse: rechaza tests que violan las reglas de CLAUDE.md ("Tests").
# Salida 2 = el mensaje de stderr vuelve a Claude para que corrija el archivo.
set -uo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "Hook check-test-integrity: falta 'jq'." >&2
  exit 2
fi

input="$(cat)"
path="$(jq -r '.tool_input.file_path // empty' <<<"$input")"

[[ "$path" == *.java ]] || exit 0
[[ "$path" == */src/test/* ]] || exit 0
[ -f "$path" ] || exit 0

violations=""
add() { violations+=$'\n'"- $1"$'\n'"$2"; }

check() { # $1 = regex ERE, $2 = mensaje
  local hits
  hits="$(grep -nE "$1" "$path" || true)"
  [ -n "$hits" ] && add "$2" "$hits"
}

# Reglas para todos los tests
check '@Disabled'                                   'Prohibido @Disabled.'
check 'Assumptions\.|assumeTrue|assumeFalse|assumingThat' 'Prohibido Assumptions: un test que se salta no prueba nada.'
check 'Thread\.sleep\('                             'Prohibido Thread.sleep: usa Awaitility.'
check 'jdbc:h2|org\.h2\.|com\.h2database'           'Prohibido H2: usa Testcontainers Postgres.'
check '@(DataJpaTest|WebMvcTest|JdbcTest|DataJdbcTest|WebFluxTest|JsonTest)' 'Prohibidos los test slices: unitario puro (*Test) o @SpringBootTest (*IT).'

case "$path" in
  *Test.java)
    check '@SpringBootTest|@Testcontainers|@Container|@ServiceConnection' \
      'Un *Test.java es unitario (sin Spring ni contenedores). Si necesita contexto, renómbralo a *IT.java.'
    ;;
  *IT.java|*E2E.java)
    check '@EmbeddedKafka'                          'Prohibido @EmbeddedKafka: usa Testcontainers Kafka.'
    check 'mock\([[:space:]]*KafkaTemplate'         'Prohibido mockear KafkaTemplate en IT/E2E.'
    hits="$(awk '/@(MockitoBean|MockBean|Mock)([^A-Za-z]|$)/{m=NR} /KafkaTemplate/ && (NR==m || NR==m+1){print NR": "$0}' "$path")"
    [ -n "$hits" ] && add 'Prohibido mockear KafkaTemplate en IT/E2E.' "$hits"
    ;;
esac

if [ -n "$violations" ]; then
  {
    echo "Test rechazado: $path"
    echo "$violations"
    echo
    echo "Corrige el test respetando CLAUDE.md (sección Tests). No debilites la aserción para que pase."
  } >&2
  exit 2
fi
exit 0
