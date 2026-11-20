# Installing a lite validator

Read [README.md](README.md) first, in particular the safety rules and the limits.

Throughout this guide:

| Placeholder | Meaning | Example |
|---|---|---|
| `NODE_DB` | `db.location` of the existing (standard) node | `/srv/radixdlt/data` |
| `LITE_DIR` | directory of the lite validator, **outside** of the top directory of `NODE_DB` and not in a home directory | `/opt/lite-1` |
| `VERSION` | version of the package | `1.4.0.0-lite.0123456789` |

The systemd unit is called `lite-<name of LITE_DIR>.service`, e.g. `lite-lite-1.service` for
`/opt/lite-1`.

## All steps at a glance

Every command in order, to copy and paste. The numbered sections below explain each step; read
them once before the first installation. Check the requirements (section 1) first, and stop at
any error: nothing of your validator is changed by these steps, and stopping is always safe.

**On the build machine** (not the validator host; see section 2):

```bash
git clone https://github.com/michael-rmp/babylon-node-lite.git
cd babylon-node-lite
docs/lite-validator/ops/build-lite-package.sh ../release
# copy ../release/lite-validator-*.tar.gz and its .sha256 to the validator host
```

**On the validator host**, in one shell session, in the directory with the two copied files.

0\. Variables: adapt these lines, everything below uses them.

```bash
NODE_DB=/srv/radixdlt/data                        # db.location of your validator (contains state_manager)
NODE_CONFIG=/etc/radixdlt/node/default.config     # configuration file of your validator
LITE_DIR=/opt/lite-1                              # directory of the lite validator
VERSION=1.4.0.0-lite.0123456789                   # from the name of the package file
PKG=$LITE_DIR/lite-validator-$VERSION
UNIT=lite-$(basename "$LITE_DIR").service
```

1\. Check the package, create the user, unpack (section 3):

```bash
sha256sum -c lite-validator-$VERSION.tar.gz.sha256
id lite >/dev/null 2>&1 || sudo useradd --system --no-create-home --shell /usr/sbin/nologin lite
sudo mkdir -p "$LITE_DIR"
sudo tar -C "$LITE_DIR" -xzf lite-validator-$VERSION.tar.gz
```

2\. Key and `environment` (section 3). Choose a password without spaces, quotes or backslashes,
and keep it (and later a copy of `keystore.ks`) in a safe place:

```bash
read -rs PW
sudo "$PKG/bin/keygen" --keystore="$LITE_DIR/keystore.ks" --password="$PW"
sudo install -m 600 /dev/null "$LITE_DIR/environment"
printf 'RADIX_NODE_KEYSTORE_PASSWORD=%s\nJAVA_OPTS=-Xms256m -Xmx512m -XX:MaxDirectMemorySize=1g\n' "$PW" \
  | sudo tee "$LITE_DIR/environment" >/dev/null
unset PW
```

`keygen` prints the public key of the lite validator; you need it to register the validator
(step 7).

3\. `mainnet.config` from a copy of your validator's configuration (section 3): the lines that
must differ are removed and appended anew. Without a configuration to copy (e.g. a node in
Docker), use `$PKG/mainnet.config.example` instead.

```bash
sudo cp "$NODE_CONFIG" "$LITE_DIR/mainnet.config"
sudo sed -i -E '/^[[:space:]]*(node\.key\.[a-z_]+|db\.location|db\.checkpoints_path|consensus\.(validator_address|use_genesis_for_validator_address)|lite\.[a-z_]+|network\.p2p\.(listen_port|broadcast_port)|api\.(core|system|prometheus|engine_state)\.(port|bind_address)|api\.system\.enable_db_checkpoint)[[:space:]]*=/d' "$LITE_DIR/mainnet.config"
sudo tee -a "$LITE_DIR/mainnet.config" >/dev/null <<EOF

# lite validator
node.key.path=$LITE_DIR/keystore.ks
db.location=$LITE_DIR/data
lite.primary_state_manager_path=$NODE_DB/state_manager
network.p2p.listen_port=30001
network.p2p.broadcast_port=30001
api.core.port=3343
api.core.bind_address=127.0.0.1
api.system.port=3344
api.system.bind_address=127.0.0.1
api.prometheus.port=3345
api.prometheus.bind_address=127.0.0.1
api.engine_state.port=3346
api.engine_state.bind_address=127.0.0.1
EOF
sudo grep -nE '^(node\.key|db\.|lite\.|consensus\.|network\.p2p\.(listen|broadcast)|api\.)' "$LITE_DIR/mainnet.config"
```

The last command shows the result: your validator's key path, database and
`consensus.validator_address` must not appear any more.

4\. Check and write the systemd service (section 4). `install.sh` changes nothing if a check
fails:

```bash
sudo MEMORY_MAX=4G "$PKG/install.sh"
systemctl cat "$UNIT"
```

5\. Disk watchdog, once per host (section 4):

```bash
sudo install -m 755 "$PKG/watchdog/radix-lite-watchdog.sh" /usr/local/sbin/
sudo install -m 644 "$PKG/watchdog/radix-lite-watchdog.service" "$PKG/watchdog/radix-lite-watchdog.timer" /etc/systemd/system/
printf 'DB_PATH=%s\nMIN_FREE_GB=150\n' "$NODE_DB" | sudo tee /etc/default/radix-lite-watchdog >/dev/null
sudo systemctl daemon-reload
sudo systemctl enable --now radix-lite-watchdog.timer
```

6\. First start, as a follower only (section 5). Opening the database takes a few minutes:

```bash
sudo systemctl start "$UNIT"
sudo journalctl -u "$UNIT" -f                     # Ctrl+C to leave; wait for the node to be UP
curl -s localhost:3344/system/health              # lite validator: "status":"UP"
curl -s localhost:3334/system/health              # validator: "missed_count" must not rise
for p in 3335 3345; do curl -s localhost:$p/prometheus/metrics | grep '^rn_ledger_state_version '; done
```

Let it run for a while (a few epoch changes) and watch memory and journal
([OPERATIONS.md](OPERATIONS.md)).

7\. Taking part in consensus (section 6). First, as the operator, register a validator on ledger
with the public key from step 2 and note its address. Then:

```bash
LITE_VALIDATOR=validator_rdx1...                  # the new validator (lite validator)
NODE_VALIDATOR=validator_rdx1...                  # your existing validator
sudo sed -i -E '/^[[:space:]]*(consensus\.validator_address|lite\.primary_validator_address)[[:space:]]*=/d' "$LITE_DIR/mainnet.config"
printf 'consensus.validator_address=%s\nlite.primary_validator_address=%s\n' "$LITE_VALIDATOR" "$NODE_VALIDATOR" \
  | sudo tee -a "$LITE_DIR/mainnet.config" >/dev/null
sudo "$PKG/install.sh"
sudo systemctl restart "$UNIT"
```

8\. Once it runs well, start it at boot (after your node):

```bash
sudo systemctl enable "$UNIT"
```

Stop at any time: `sudo systemctl stop "$UNIT"`. Updates, the way back and removal: sections 7 and 8.

## 1. Requirements

**Who does what.** Most steps can be done by an operator or by an assistant (for example a coding
agent) with shell access. Three things are always the operator's: registering and staking the
validator on ledger (step 6, needs a wallet and XRD), choosing and keeping the keystore password,
and granting `sudo` on the validator host.

**Validator host**

- Linux with systemd; a Babylon node of the **same release** as the package (`v1.4.0.0`),
  installed **directly on the host** (systemd service, e.g. `babylon-node.service`) and writing its
  database to `NODE_DB`. This is the tested setup.
- **The node must itself be an active validator.** It then commits every block at once, and the
  lite validator follows within a few hundred milliseconds. A plain full node as the source is not
  supported and not tested: it learns about new blocks only through ledger sync, so the lite
  validator would follow late and miss more proposals. (The lite validator also needs the node's
  validator address, `lite.primary_validator_address`, to take part in consensus.)
- **Nodes in Docker** (e.g. set up with the `babylonnode` CLI in Docker mode) are **not tested**.
  There the database is in a host directory mounted into the container, usually owned by the
  container's user, and the configuration is generated from environment variables
  (`docker/config/default.config.envsubst`), so there is no `mainnet.config` to copy. A lite
  validator can in principle read such a database from the host (step 3: write a complete
  `mainnet.config` from the example); check the permissions carefully and treat it as untested.
- **Java 17 exactly** (e.g. `openjdk-17-jre-headless`); the package uses the system Java. The code
  is compiled with preview features of Java 17 and does not run on other versions (also not on
  21); `install.sh` checks this.
- Per lite validator: ~3.5 GB RAM (measured peak 3.4 GB with `-Xmx768m`, see
  [TESTING.md](TESTING.md)) and a few GB of disk in `LITE_DIR`. Check first that the host has this
  memory to spare while the validator runs at its peak; the lite validator must not push the
  validator into memory pressure.
- The state database of the validator must be readable, but not writable, for the user of the lite
  validator. A standard installation (database owned by the node's user, directories 755, files
  644) fulfils this. `install.sh` checks it and refuses otherwise. Never give the lite user write
  access.

**Build machine** (not the validator host)

- JDK 17, Rust via `rustup` (the version, 1.92.0, is chosen automatically from
  `core-rust/rust-toolchain.toml`), ~16 GB RAM (or swap), ~30 GB disk. On Debian/Ubuntu:
  ```bash
  sudo apt-get install -y build-essential git curl unzip pkg-config libssl-dev libclang-dev \
    openjdk-17-jdk-headless python3
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain none
  . "$HOME/.cargo/env"
  ```
- The same or an older Linux distribution than the validator host (the native library is linked
  against the build machine's glibc).

## 2. Build the package

On the build machine:

```bash
git clone https://github.com/michael-rmp/babylon-node-lite.git
cd babylon-node-lite
docs/lite-validator/ops/build-lite-package.sh ../release
```

The script builds the Rust library (release) and the Java parts and writes
`lite-validator-VERSION.tar.gz` and `lite-validator-VERSION.tar.gz.sha256` (~10 minutes after the
first build). The package contains only the lite validator's entry point (`bin/lite-validator`)
and `bin/keygen`; it cannot start a standard node. The build is reproducible: the same commit,
built on another machine with the same distribution, gives the same `libcorerust.so` (its SHA-256 is
in the `VERSION` file of the package; paths of the build machine are mapped to `/build` and
`/cargo`), so a published package can be checked by building it again.

Copy both files to the validator host and check the checksum there:

```bash
sha256sum -c lite-validator-VERSION.tar.gz.sha256
```

## 3. Prepare the directory

On the validator host:

```bash
# A system user of its own, without login and without a home directory:
sudo useradd --system --no-create-home --shell /usr/sbin/nologin lite

sudo mkdir -p /opt/lite-1
sudo tar -C /opt/lite-1 -xzf lite-validator-VERSION.tar.gz
```

### Key

The lite validator needs a key of its own. Never copy the validator's keystore.

```bash
read -rs PW   # the keystore password, not shown and not stored in the shell history
sudo /opt/lite-1/lite-validator-VERSION/bin/keygen --keystore=/opt/lite-1/keystore.ks --password="$PW"
sudo /opt/lite-1/lite-validator-VERSION/bin/keygen --keystore=/opt/lite-1/keystore.ks --password="$PW" --show-public-key
unset PW
```

Keep a backup of `keystore.ks` and the password in a safe place.

### environment

`/opt/lite-1/environment` (read by systemd; `install.sh` makes it readable for root and the lite
user only):

```
RADIX_NODE_KEYSTORE_PASSWORD=<the password>
JAVA_OPTS=-Xms256m -Xmx512m -XX:MaxDirectMemorySize=1g
```

The Java heap needs little: the live set was below 100 MB in all measurements, also as an active
validator. 512 MB leaves ample room.

### mainnet.config

`/opt/lite-1/mainnet.config`, two ways (both templates are in the package):

- **With a validator configuration to copy** (the usual case): copy the validator's configuration
  file (often `/etc/radixdlt/node/default.config` or `mainnet.config`; network, seed nodes and so
  on stay the same) to `/opt/lite-1/mainnet.config` and change it with the lines from
  [`mainnet.config.lite-lines.example`](ops/package/mainnet.config.lite-lines.example), as listed
  below.
- **Without one** (e.g. a node in Docker): start from the complete
  [`mainnet.config.example`](ops/package/mainnet.config.example) and fill in the seed nodes (the
  validator's list, or the current mainnet seed nodes from the official Radix node documentation)
  and the host's public IP address. The mainnet genesis is built in.

What must be different from the validator:

- `node.key.path=/opt/lite-1/keystore.ks` and `db.location=/opt/lite-1/data` (both are checked by
  `install.sh`).
- `lite.primary_state_manager_path=NODE_DB/state_manager`: this line makes the node a lite
  validator.
- **Ports of its own** for P2P and all APIs, e.g. 30001 and 3343–3346. The start is refused with
  a default port (30000, 3333–3336), also if a port line is missing: otherwise a lite validator
  that starts before the validator (e.g. after a reboot) could take the validator's ports.
- `api.core.bind_address` and `api.engine_state.bind_address` must be IP addresses
  (`127.0.0.1`), not host names.
- **Remove** the validator's `consensus.validator_address` for now. The first start is as a
  follower only (step 5).
- Leave `api.system.enable_db_checkpoint` off (a lite validator refuses to create checkpoints,
  they would hard-link the validator's files), and leave the `db.*` index settings as they are.

All settings: [CONFIGURATION.md](CONFIGURATION.md).

## 4. Run install.sh

```bash
sudo MEMORY_MAX=4G /opt/lite-1/lite-validator-VERSION/install.sh
```

The script checks the configuration and that the lite user can read, but not write, the
validator's database; sets owners and permissions; creates `data/` and `logs/`; and writes the
unit `lite-lite-1.service`. It starts nothing and enables nothing.

`MEMORY_MAX` is the hard memory limit of the unit (default 6G). With `-Xmx512m`, 4G leaves enough
room in our measurements. If the lite validator reaches it, the kernel stops the lite validator
and systemd restarts it after 120 s; the validator is not affected.

Have a look at the unit: `systemctl cat lite-lite-1.service`. Under the top directory of
`NODE_DB` the process sees only the `state_manager` directory, read-only.

### Watchdog (once per host)

The watchdog stops all `lite-*.service` units when the disk of the validator runs low. A lite
process that hangs can keep deleted database files of the validator open, which would fill the
disk.

```bash
cd /opt/lite-1/lite-validator-VERSION/watchdog
sudo install -m 755 radix-lite-watchdog.sh /usr/local/sbin/
sudo install -m 644 radix-lite-watchdog.default /etc/default/radix-lite-watchdog
sudo install -m 644 radix-lite-watchdog.service radix-lite-watchdog.timer /etc/systemd/system/
sudoedit /etc/default/radix-lite-watchdog   # set DB_PATH=NODE_DB; MIN_FREE_GB as you see fit
sudo systemctl daemon-reload
sudo systemctl enable --now radix-lite-watchdog.timer
```

While `DB_PATH` is empty, the watchdog stops every lite validator, so a forgotten setting is
noticed. Test it once while a lite validator runs: set `MIN_FREE_GB` above the free space, wait
up to 90 seconds, check that the unit is stopped and that
`journalctl -u radix-lite-watchdog.service` shows `only …G free … -> stopping lite-*.service`.
Then set the threshold back.

## 5. First start: follower only

```bash
sudo systemctl start lite-lite-1.service
journalctl -u lite-lite-1.service -f
```

Opening the validator's database takes a few minutes on mainnet (all SST files are read once,
~4–5 minutes in our measurements). Then:

```bash
curl -s localhost:3344/system/health            # lite validator: "status":"UP"
curl -s localhost:3334/system/health            # validator: "missed_count" must not rise
for p in 3335 3345; do curl -s localhost:$p/prometheus/metrics | grep '^rn_ledger_state_version '; done
```

The state version of the lite validator should be equal to the validator's or a few versions
behind. Let it run for a while (at least a few epoch changes) and watch the validator, the memory
(`systemctl show -p MemoryCurrent lite-lite-1.service`) and the journal (see
[OPERATIONS.md](OPERATIONS.md)).

## 6. Taking part in consensus

1. **Operator:** create and register a validator on ledger with the **public key of the lite
   validator** (printed by `keygen` in step 3), exactly as for any new Radix validator: a
   transaction that creates the validator component with this key and a fee factor (it costs a
   creation fee in XRD and gives you the owner badge), then registering it and accepting stake
   with the owner badge. Follow the official Radix documentation for validators
   (https://docs.radixdlt.com), which is kept up to date; nothing about this is specific to a lite
   validator. The result is the validator address `validator_rdx1…`. The validator takes part when
   its stake puts it into the active set (top 100), from the next epoch on.
2. In `/opt/lite-1/mainnet.config` set:
   ```
   consensus.validator_address=<the address of the new validator>
   lite.primary_validator_address=<the address of the existing validator>
   ```
   `lite.primary_validator_address` is mandatory together with `consensus.validator_address`;
   the start is refused if both are the same or if the lite validator's key is registered for
   the existing validator (exit code 78).
3. `sudo /opt/lite-1/lite-validator-VERSION/install.sh` again (checks the new lines), then
   `sudo systemctl restart lite-lite-1.service`.

In the epoch of the start, the lite validator does not vote (by design); it votes from the next
epoch on. P2P: the lite validator ran fine on mainnet with its P2P port closed in the firewall
(it connects out); opening it improves its connectivity.

Once it runs well, enable it for reboots: `sudo systemctl enable lite-lite-1.service`. The unit
starts after `babylon-node.service`.

## 7. Update and way back

A new release of the node: update the standard node and the lite validator together, with a
package built from the matching release.

```bash
sudo tar -C /opt/lite-1 -xzf lite-validator-NEWVERSION.tar.gz
sudo systemctl stop lite-lite-1.service
sudo /opt/lite-1/lite-validator-NEWVERSION/install.sh     # the unit now points to the new package
sudo systemctl start lite-lite-1.service
```

The old package directory stays. Way back: stop, run the old package's `install.sh`, start.
`data/` (safety store, vertex store, address book) is kept across updates. **Never delete or reset
`data/consensus_safety_store` while the validator is registered**: after a restart in the same
epoch, the lite validator could otherwise vote twice in a round.

## 8. Removal

```bash
sudo systemctl disable --now lite-lite-1.service
sudo rm /etc/systemd/system/lite-lite-1.service && sudo systemctl daemon-reload
```

Unregister the validator on ledger first if it takes part in consensus. `/opt/lite-1` can then be
deleted (keep the keystore if the validator may come back). Nothing of the standard node is
changed by any of these steps.
