#!/usr/bin/env bash
# OrderFlow guided demo: walks through the order saga against a running local stack.
#
#   docker compose -f deploy/docker-compose.yml up -d --build
#   ./deploy/demo.sh            # runs straight through
#   DEMO_PAUSE=1 ./deploy/demo.sh   # waits for Enter between steps (good for live demos)
#
# Requires: curl, jq, docker (only for the invoice PDF check).
set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
KEYCLOAK="${KEYCLOAK:-http://localhost:8180}"
COMPOSE_FILE="$(cd "$(dirname "$0")" && pwd)/docker-compose.yml"
SAGA_TIMEOUT="${SAGA_TIMEOUT:-60}"

# ---------- output helpers ----------
if [ -t 1 ]; then B=$'\e[1m'; G=$'\e[32m'; R=$'\e[31m'; Y=$'\e[33m'; C=$'\e[36m'; N=$'\e[0m'; else B= G= R= Y= C= N=; fi
STEP=0
step() {
  STEP=$((STEP + 1))
  if [ "${DEMO_PAUSE:-0}" = "1" ] && [ "$STEP" -gt 1 ]; then read -r -p "${Y}  [Enter] next step${N}" _; fi
  printf '\n%s== %d. %s ==%s\n' "$B" "$STEP" "$1" "$N"
}
info() { printf '  %s\n' "$*"; }
ok()   { printf '  %s✔ %s%s\n' "$G" "$*" "$N"; }
fail() { printf '  %s✘ %s%s\n' "$R" "$*" "$N" >&2; exit 1; }
# jq on Windows can emit CRLF; strip it so values compare cleanly.
jqr()  { jq -r "$@" | tr -d '\r'; }

for bin in curl jq; do command -v "$bin" >/dev/null || fail "'$bin' is required"; done

new_uuid() {
  if command -v uuidgen >/dev/null; then uuidgen | tr 'A-Z' 'a-z'
  elif [ -r /proc/sys/kernel/random/uuid ]; then cat /proc/sys/kernel/random/uuid
  else od -An -N16 -tx1 /dev/urandom | tr -d ' \n' | sed -E 's/(.{8})(.{4})(.{4})(.{4})(.{12})/\1-\2-\3-\4-\5/'
  fi
}

# Delivery date one week ahead (GNU date, then BSD/macOS date).
DELIVERY=$(date -d '+7 days' +%F 2>/dev/null || date -v+7d +%F)

token() {
  curl -fsS "$KEYCLOAK/realms/orderflow/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=orderflow-cli -d "username=$1" -d "password=$1" \
    | jqr .access_token
}

# POST /api/orders → prints "<http_status> <body>"
create_order() { # $1 token, $2 idempotency key, $3 json body
  local out; out=$(mktemp)
  curl -sS -o "$out" -w '%{http_code}' -X POST "$GATEWAY/api/orders" \
    -H "Authorization: Bearer $1" -H "Idempotency-Key: $2" -H 'Content-Type: application/json' -d "$3"
  printf ' '; cat "$out"; rm -f "$out"
}

get_order() { curl -fsS "$GATEWAY/api/orders/$2" -H "Authorization: Bearer $1"; }

# Polls until the order leaves PENDING; prints the final order JSON.
wait_final() { # $1 token, $2 orderId
  local deadline=$((SECONDS + SAGA_TIMEOUT)) order status
  while :; do
    order=$(get_order "$1" "$2"); status=$(jqr .status <<<"$order")
    [ "$status" != "PENDING" ] && { printf '\n' >&2; echo "$order"; return; }
    [ "$SECONDS" -ge "$deadline" ] && { printf '\n' >&2; fail "still PENDING after ${SAGA_TIMEOUT}s"; }
    printf '.' >&2; sleep 1
  done
}

# ---------------------------------------------------------------------------
step "Preflight"
curl -fsS -o /dev/null "$KEYCLOAK/realms/orderflow" || fail "Keycloak not reachable at $KEYCLOAK — is the stack up?"
ok "Keycloak is up"
ACME=$(token buyer-acme);     ok "Got a JWT for buyer-acme (company ACME)"
GLOBEX=$(token buyer-globex); ok "Got a JWT for buyer-globex (company GLOBEX)"
OPS=$(token ops);             ok "Got a JWT for ops (back-office role)"

step "Catalog with per-company pricing"
info "GET /api/catalog/products — ACME has a 10% discount, ops sees base prices"
ACME_PRICES=$(curl -fsS "$GATEWAY/api/catalog/products?size=3" -H "Authorization: Bearer $ACME")
OPS_PRICES=$(curl -fsS "$GATEWAY/api/catalog/products?size=3" -H "Authorization: Bearer $OPS")
paste <(jqr '.content[] | "\(.sku)  \(.name)"' <<<"$OPS_PRICES") \
      <(jqr '.content[] | "base \(.unitPrice)"' <<<"$OPS_PRICES") \
      <(jqr '.content[] | "ACME \(.unitPrice)"' <<<"$ACME_PRICES") | sed 's/^/  /'

step "Place an order (happy path)"
KEY=$(new_uuid)
BODY=$(jq -cn --arg d "$DELIVERY" '{deliveryDate:$d, items:[{sku:"SKU-0001",quantity:2},{sku:"SKU-0002",quantity:3}]}')
info "POST /api/orders  Idempotency-Key: $KEY"
info "$BODY"
read -r CODE RESP <<<"$(create_order "$ACME" "$KEY" "$BODY")"
[ "$CODE" = 202 ] || fail "expected 202, got $CODE: $RESP"
ORDER_ID=$(jqr .orderId <<<"$RESP")
ok "202 Accepted — orderId $ORDER_ID, status PENDING (the saga runs asynchronously)"

step "Retry with the same Idempotency-Key"
read -r CODE RESP <<<"$(create_order "$ACME" "$KEY" "$BODY")"
[ "$CODE" = 202 ] && [ "$(jqr .orderId <<<"$RESP")" = "$ORDER_ID" ] \
  && ok "Same key → same orderId ($CODE). No duplicate order was created." \
  || fail "idempotency check failed: $CODE $RESP"
OTHER=$(jq -c '.items[0].quantity = 99' <<<"$BODY")
read -r CODE RESP <<<"$(create_order "$ACME" "$KEY" "$OTHER")"
[ "$CODE" = 422 ] && ok "Same key, different body → 422 $(jqr .code <<<"$RESP")" || fail "expected 422, got $CODE"

step "Watch the saga: stock → credit → shipping"
info "order-service orchestrates inventory, credit and shipping over Kafka"
printf '  waiting' >&2
FINAL=$(wait_final "$ACME" "$ORDER_ID")
[ "$(jqr .status <<<"$FINAL")" = CONFIRMED ] || fail "expected CONFIRMED: $FINAL"
ok "CONFIRMED — total $(jqr '"\(.total) \(.currency)"' <<<"$FINAL")"
jq '{orderId, companyId, status, total, items}' <<<"$FINAL" | sed 's/^/  /'

step "Invoice PDF generated by a Lambda"
info "On CONFIRMED, order-service enqueues an InvoiceJob (SQS); invoice-fn renders the PDF to S3"
PDF="s3://orderflow-invoices/invoices/ACME/$ORDER_ID.pdf"
if command -v docker >/dev/null; then
  deadline=$((SECONDS + 60)); found=0
  while [ "$SECONDS" -lt "$deadline" ]; do
    if docker compose -f "$COMPOSE_FILE" exec -T localstack awslocal s3 ls "$PDF" >/dev/null 2>&1; then found=1; break; fi
    sleep 2
  done
  [ "$found" = 1 ] && ok "Found $PDF" || info "${Y}PDF not there yet — check later with: docker compose -f deploy/docker-compose.yml exec localstack awslocal s3 ls $PDF${N}"
else
  info "docker not on PATH; skipping the S3 check"
fi

step "Business rejection: credit limit exceeded"
info "GLOBEX has a 500.00 credit line; SKU-0020 x3 = 600.00"
BODY2=$(jq -cn --arg d "$DELIVERY" '{deliveryDate:$d, items:[{sku:"SKU-0020",quantity:3}]}')
read -r CODE RESP <<<"$(create_order "$GLOBEX" "$(new_uuid)" "$BODY2")"
[ "$CODE" = 202 ] || fail "expected 202, got $CODE: $RESP"
REJ_ID=$(jqr .orderId <<<"$RESP")
printf '  waiting' >&2
FINAL2=$(wait_final "$GLOBEX" "$REJ_ID")
ok "$(jqr '"\(.status) — reason \(.reason)"' <<<"$FINAL2") (the stock reservation was compensated)"

step "Multi-tenant isolation"
CODE=$(curl -sS -o /dev/null -w '%{http_code}' "$GATEWAY/api/orders/$ORDER_ID" -H "Authorization: Bearer $GLOBEX")
[ "$CODE" = 404 ] && ok "GLOBEX asking for ACME's order → 404 (not 403: it doesn't leak that it exists)" || fail "expected 404, got $CODE"
CODE=$(curl -sS -o /dev/null -w '%{http_code}' "$GATEWAY/api/orders/$ORDER_ID" -H "Authorization: Bearer $OPS")
[ "$CODE" = 200 ] && ok "ops → 200 (back-office sees every company)" || fail "expected 200, got $CODE"

step "Where to look next"
info "Distributed trace (one trace across gateway → order → inventory/credit/shipping):"
info "  ${C}http://localhost:16686/search?service=order-service&tags=%7B%22order.id%22%3A%22$ORDER_ID%22%7D${N}"
info "Grafana dashboard 'OrderFlow' (sagas, outbox, circuit breaker, DLQs): ${C}http://localhost:3000${N}"
info "Prometheus alerts: ${C}http://localhost:9090/alerts${N}"
info "Logs in Kibana (index orderflow-logs-*), search orderId:$ORDER_ID: ${C}http://localhost:5601${N}"
info "Eureka registry: ${C}http://localhost:8761${N}"
printf '\n%sDemo finished.%s\n' "$G$B" "$N"
