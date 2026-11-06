#!/usr/bin/env bash
# Phase J: daily database backup. Run from cron on the server, e.g.
#   15 2 * * *  /opt/expert-erp/infra/backup.sh >> /var/log/erp-backup.log 2>&1
# Keeps BACKUP_KEEP_DAYS days (default 30) of compressed dumps in BACKUP_DIR (default ./backups).
# Copy BACKUP_DIR off the server too (another disk, cloud storage): a backup on the same disk is not a backup.
set -euo pipefail
cd "$(dirname "$0")/.."
BACKUP_DIR="${BACKUP_DIR:-./backups}"
KEEP="${BACKUP_KEEP_DAYS:-30}"
CONTAINER="${PG_CONTAINER:-erp-postgres}"
DB="${PG_DB:-inventory_db}"
DB_USER="${PG_USER:-inventory}"
mkdir -p "$BACKUP_DIR"
file="$BACKUP_DIR/erp-$(date +%Y%m%d-%H%M%S).dump"
# Custom format: compressed, restorable table by table with pg_restore
docker exec "$CONTAINER" pg_dump -U "$DB_USER" -d "$DB" -Fc > "$file.part"
mv "$file.part" "$file"
size=$(du -h "$file" | cut -f1)
# A dump that pg_restore cannot list is broken: fail loudly
docker exec -i "$CONTAINER" pg_restore -l > /dev/null < "$file"
# Old dumps out (only erp-*.dump files in the backup folder)
find "$BACKUP_DIR" -maxdepth 1 -name 'erp-*.dump' -mtime +"$KEEP" -delete
echo "$(date '+%F %T') backup ok: $file ($size)"
