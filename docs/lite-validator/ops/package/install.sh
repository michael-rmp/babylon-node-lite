#!/bin/sh
# Sets up a lite validator in a directory that already contains:
#   keystore.ks, environment, mainnet.config  (provided by the operator)
#   lite-validator-<version>/                 (this package, unpacked)
#
#   sudo <directory>/lite-validator-<version>/install.sh
#
# The script checks the configuration, sets owners and permissions, creates data/ and logs/ and
# writes the systemd unit lite-<name>.service (<name> = name of the directory).
# It starts nothing and enables nothing. Nothing of the standard node is changed.
set -eu

PKG=$(cd "$(dirname "$0")" && pwd -P)
DIR=$(dirname "$PKG")
NAME=$(basename "$DIR")
UNIT="lite-$NAME.service"
LITE_USER=${LITE_USER:-lite}
MEMORY_MAX=${MEMORY_MAX:-6G}
# Only for testing the script without root: writes the unit to \$UNIT_DIR and skips everything
# that needs root (check of the lite user, owners and permissions, systemctl).
UNIT_DIR=${UNIT_DIR:-/etc/systemd/system}
TEST_ONLY=${TEST_ONLY:-0}

fail() { echo "ERROR: $1" >&2; exit 1; }
conf() { sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$DIR/mainnet.config" | tail -1; }

if [ "$TEST_ONLY" != 1 ]; then
  [ "$(id -u)" = 0 ] || fail "please run with sudo"
  id "$LITE_USER" >/dev/null 2>&1 || fail "user $LITE_USER does not exist"
fi
for f in keystore.ks environment mainnet.config; do
  [ -f "$DIR/$f" ] || fail "$DIR/$f is missing"
done
[ -x "$PKG/bin/lite-validator" ] || fail "$PKG/bin/lite-validator is missing"
[ -f "$PKG/lib/native/libcorerust.so" ] || fail "$PKG/lib/native/libcorerust.so is missing"
command -v java >/dev/null 2>&1 || [ -n "${JAVA_HOME:-}" ] || fail "java not found"

PRIMARY=$(conf 'lite\.primary_state_manager_path')
OWN=$(conf 'db\.location')
KEY=$(conf 'node\.key\.path')
[ -n "$PRIMARY" ] || fail "lite.primary_state_manager_path is missing in mainnet.config"
[ -f "$PRIMARY/CURRENT" ] || fail "$PRIMARY contains no database (no CURRENT file)"
[ "$OWN" = "$DIR/data" ] || fail "db.location must be $DIR/data (is: ${OWN:-not set})"
[ "$KEY" = "$DIR/keystore.ks" ] || fail "node.key.path must be $DIR/keystore.ks (is: ${KEY:-not set})"
# Taking part in consensus (like the start check of the lite node): an own validator address only
# together with the address of the validator whose database is read, and never the same one.
OWN_VALIDATOR=$(conf 'consensus\.validator_address')
PRIMARY_VALIDATOR=$(conf 'lite\.primary_validator_address')
if [ -n "$OWN_VALIDATOR" ]; then
  [ -n "$PRIMARY_VALIDATOR" ] || fail "consensus.validator_address is set, lite.primary_validator_address is missing"
  [ "$OWN_VALIDATOR" != "$PRIMARY_VALIDATOR" ] \
    || fail "consensus.validator_address is the address of the primary validator (lite.primary_validator_address)"
fi
PRIMARY_TOP=/$(echo "$PRIMARY" | cut -d/ -f2)
case "$DIR/" in "$PRIMARY_TOP"/*) fail "$DIR is below $PRIMARY_TOP; the directory must be outside of it";; esac
# The unit hides home directories (ProtectHome):
case "$DIR/" in /home/*|/root/*|/run/user/*)
  [ "$TEST_ONLY" = 1 ] || fail "$DIR is in a home directory; please use e.g. /opt";; esac

mkdir -p "$DIR/data" "$DIR/logs"
if [ "$TEST_ONLY" != 1 ]; then
  # The lite user must not be able to write anything in the database of the validator:
  if su -s /bin/sh "$LITE_USER" -c "test -w '$PRIMARY' || test -w '$PRIMARY/CURRENT'"; then
    fail "$LITE_USER can write in $PRIMARY"
  fi
  su -s /bin/sh "$LITE_USER" -c "test -r '$PRIMARY/CURRENT'" \
    || fail "$LITE_USER cannot read $PRIMARY/CURRENT"

  chown root:"$LITE_USER" "$DIR" "$DIR/keystore.ks" "$DIR/environment" "$DIR/mainnet.config"
  chmod 0750 "$DIR"
  chmod 0640 "$DIR/keystore.ks" "$DIR/environment" "$DIR/mainnet.config"
  chown -R root:root "$PKG"
  chmod -R go-w "$PKG"
  chown -R "$LITE_USER":"$LITE_USER" "$DIR/data" "$DIR/logs"
  chmod 0700 "$DIR/data"
fi

cat > "$UNIT_DIR/$UNIT" <<UNIT_EOF
# Radix lite validator "$NAME" (written by $PKG/install.sh).
# The name matches lite-*.service: the watchdog covers this unit.
# Abort at any time: systemctl stop $UNIT   (nothing of the standard node is changed)
[Unit]
Description=Radix lite validator $NAME
After=network-online.target babylon-node.service
StartLimitIntervalSec=3600
StartLimitBurst=3

[Service]
Type=simple
User=$LITE_USER
Group=$LITE_USER
WorkingDirectory=$DIR
EnvironmentFile=$DIR/environment
Environment=RADIXDLT_HOME=$DIR
ExecStart=$PKG/bin/lite-validator -config $DIR/mainnet.config
# A restart opens the database of the validator again (minutes, a lot of read load): no loop.
Restart=on-failure
RestartSec=120
SuccessExitStatus=143
# 78: the lite node found a state that a restart does not fix (an error in the configuration,
# e.g. a default port; or a foreign validator identity). No restart, the operator has to look.
RestartPreventExitStatus=78
TimeoutStopSec=60

# File system: below $PRIMARY_TOP only the state database of the validator is visible, read-only.
TemporaryFileSystem=$PRIMARY_TOP:ro
BindReadOnlyPaths=$PRIMARY
ProtectSystem=strict
ReadWritePaths=$DIR/data $DIR/logs
ProtectHome=yes
PrivateTmp=yes
NoNewPrivileges=yes
CapabilityBoundingSet=
PrivateDevices=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictSUIDSGID=yes
LockPersonality=yes

# Resources: when memory runs short, the lite validator dies, not the validator.
MemoryMax=$MEMORY_MAX
MemorySwapMax=0
OOMScoreAdjust=1000
OOMPolicy=kill
LimitNOFILE=8192
TasksMax=1024
Nice=5

[Install]
WantedBy=multi-user.target
UNIT_EOF
[ "$TEST_ONLY" = 1 ] || systemctl daemon-reload

echo "OK: $UNIT written (not started, not enabled)."
echo "  Directory:          $DIR"
echo "  Package:            $PKG"
echo "  Validator database: $PRIMARY (read-only)"
echo "  Memory limit:       $MEMORY_MAX"
echo "Start:   systemctl start $UNIT"
echo "Log:     journalctl -u $UNIT -f"
echo "Stop:    systemctl stop $UNIT"
