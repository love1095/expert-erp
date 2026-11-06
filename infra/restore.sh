#!/usr/bin/env bash
# Phase J: restore a backup made by backup.sh.
#   infra/restore.sh backups/erp-20261107-021500.dump            -> into a spare database inventory_restore (safe: proves the backup works)
#   infra/restore.sh backups/erp-20261107-021500.dump --replace  -> over the live database (stop the app first:
#                                                                   docker compose stop inventory-service)
set -euo pipefail
cd "$(dirname "$0")/.."
file="${1:?usage: infra/restore.sh <dump file> [--replace]}"
CONTAINER="${PG_CONTAINER:-erp-postgres}"
DB_USER="${PG_USER:-inventory}"
DB="${PG_DB:-inventory_db}"
if [ "${2:-}" = "--replace" ]; then
  target="$DB"
  read -r -p "This replaces ALL data in $DB with $file. Type the database name to go on: " answer
  [ "$answer" = "$DB" ] || { echo "Stopped."; exit 1; }
else
  target="inventory_restore"
fi
docker exec "$CONTAINER" psql -U "$DB_USER" -d postgres -c "DROP DATABASE IF EXISTS $target WITH (FORCE);" -c "CREATE DATABASE $target OWNER $DB_USER;"
docker exec -i "$CONTAINER" pg_restore -U "$DB_USER" -d "$target" --no-owner < "$file"
echo "Restored $file into $target. Quick look:"
docker exec "$CONTAINER" psql -U "$DB_USER" -d "$target" -c \
  "SELECT (SELECT COUNT(*) FROM products) AS products, (SELECT COUNT(*) FROM sale_orders) AS sale_orders, (SELECT COUNT(*) FROM invoices) AS invoices, (SELECT MAX(created_at) FROM sale_orders) AS last_order;"
