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

## 1. Requirements

**Validator host**

- Linux with systemd; a Babylon node of the **same release** as the package (`v1.4.0.0`),
  running as a systemd service and writing its database to `NODE_DB`.
- Java 17 (e.g. `openjdk-17-jre-headless`); the package uses the system Java.
- Per lite validator: ~3.5 GB RAM (measured peak 3.4 GB with `-Xmx768m`, see
  [TESTING.md](TESTING.md)) and a few GB of disk in `LITE_DIR`. Check first that the host has this
  memory to spare while the validator runs at its peak; the lite validator must not push the
  validator into memory pressure.
- The state database of the validator must be readable, but not writable, for the user of the lite
  validator. A standard installation (database owned by the node's user, directories 755, files
  644) fulfils this. `install.sh` checks it and refuses otherwise. Never give the lite user write
  access.

**Build machine** (not the validator host)

- Rust 1.92.0 (`rustup`), JDK 17, git, ~16 GB RAM (or swap), ~30 GB disk.
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
and `bin/keygen`; it cannot start a standard node. The build is reproducible: building the same
commit twice gives the same `libcorerust.so` (its SHA-256 is in the `VERSION` file of the package).

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

Start from a copy of the validator's `mainnet.config` (network, genesis, seed nodes and so on stay
the same) and then change it with the lines from
[`mainnet.config.lite-lines.example`](ops/package/mainnet.config.lite-lines.example):

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

1. Register a validator on ledger with the **public key of the lite validator** (step 3), in the
   same way as for any Radix validator. It takes part when it is in the active set.
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
