#!/bin/bash
# Una base y un usuario por servicio (SPEC §3.3).
set -euo pipefail

create() {
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
    -c "CREATE USER $1 WITH PASSWORD '$2';" \
    -c "CREATE DATABASE $1 OWNER $1;" \
    -c "REVOKE ALL ON DATABASE $1 FROM PUBLIC;"
}

create catalog "$CATALOG_DB_PASSWORD"
create orders "$ORDERS_DB_PASSWORD"
create inventory "$INVENTORY_DB_PASSWORD"
create credit "$CREDIT_DB_PASSWORD"
create shipping "$SHIPPING_DB_PASSWORD"
create keycloak "$KEYCLOAK_DB_PASSWORD"
