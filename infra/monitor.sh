#!/usr/bin/env bash
# Phase J: uptime check, run every 5 minutes from cron on another machine (or point an uptime service at the same URL):
#   */5 * * * *  ERP_URL=https://erp.example.com ALERT_EMAIL=owner@example.com /opt/expert-erp/infra/monitor.sh
# Alerts once when the app stops answering and once when it is back (e-mail needs a working `mail` command).
set -uo pipefail
URL="${ERP_URL:-http://localhost:8080}/api/health"
STATE="${MONITOR_STATE:-/tmp/erp-monitor.state}"
code=$(curl -s -o /dev/null -m 10 -w '%{http_code}' "$URL")
before=$(cat "$STATE" 2>/dev/null || echo up)
alert() {
  echo "$(date '+%F %T') $1"
  if [ -n "${ALERT_EMAIL:-}" ] && command -v mail > /dev/null; then echo "$1" | mail -s "Expert ERP: $1" "$ALERT_EMAIL"; fi
}
if [ "$code" = "200" ]; then
  [ "$before" = "down" ] && alert "back up ($URL)"
  echo up > "$STATE"
else
  [ "$before" = "up" ] && alert "DOWN: $URL answered $code"
  echo down > "$STATE"
fi
