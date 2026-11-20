#!/bin/sh
# Watchdog for lite validator processes. Stops lite-*.service when free disk space runs low.
# A lite process that hangs can keep deleted SST files of the validator open; the disk of the
# validator then fills up. Better a stopped lite validator than a stopped validator.
set -u
UNITS='lite-*.service'
MIN_FREE_GB="${MIN_FREE_GB:-150}"
stop_lite() {
  echo "radix-lite-watchdog: $1 -> stopping $UNITS"
  systemctl kill -s KILL "$UNITS" 2>/dev/null
  systemctl stop --no-block "$UNITS" 2>/dev/null
}
[ -z "${DB_PATH:-}" ] && { stop_lite "DB_PATH not set"; exit 1; }
FREE=$(df --output=avail -BG "$DB_PATH" 2>/dev/null | tail -1 | tr -dc 0-9)
[ -z "$FREE" ] && { stop_lite "free space on $DB_PATH cannot be determined"; exit 1; }
[ "$FREE" -lt "$MIN_FREE_GB" ] && stop_lite "only ${FREE}G free on $DB_PATH (threshold ${MIN_FREE_GB}G)"
exit 0
