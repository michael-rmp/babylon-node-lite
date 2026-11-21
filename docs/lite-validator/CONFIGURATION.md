# Configuration

A lite validator uses the normal node configuration (`mainnet.config`) plus the `lite.*` settings
below. Templates: [`ops/package/mainnet.config.lite-lines.example`](ops/package/mainnet.config.lite-lines.example)
(the lines to change in a copy of the validator's configuration) and
[`ops/package/mainnet.config.example`](ops/package/mainnet.config.example) (a complete file).

All checks run before anything is opened or bound. A configuration error ends the process with
exit code 78 and a message that names the setting; systemd does not restart it (see
[Exit codes](#exit-codes)).

## Settings of the lite validator

Unknown `lite.*` keys (e.g. a typo) are refused, and the message lists the valid ones.

| Key | Default | Allowed | Meaning |
|---|---|---|---|
| `lite.primary_state_manager_path` | – (mandatory) | path | The `state_manager` directory of the validator. Setting it makes the node a lite validator. Only read. Must not be writable for the process. |
| `lite.primary_validator_address` | – | `validator_rdx1…` | The validator whose database is read. Mandatory together with `consensus.validator_address`; must differ from it. Checked at the start: it must be a valid validator address of the network in `network.id`. |
| `lite.max_open_files` | 500 | -1 or ≥ 20 | RocksDB `max_open_files` for the validator's database. Each open file costs ~0.4 MB on mainnet; -1 (all files) is not an option there. |
| `lite.block_cache_mb` | 256 | ≥ 8 | Block cache, shared by all column families. |
| `lite.cache_index_and_filter_blocks` | false | true/false | Keep index and filter blocks in the block cache (bounded, but re-read when evicted) instead of in memory while the file is open. |
| `lite.wal_links` | true | true/false | Keep links to the validator's WAL files in the own directory, so that a catch-up does not list the validator's whole database directory. |
| `lite.check_interval_ms` | 50 | ≥ 10 | How often to look (cheaply) whether the validator has written. |
| `lite.min_follow_interval_ms` | 500 | ≥ 0 | Minimum time between two catch-ups (a catch-up costs ~80–100 ms on mainnet). Not applied while an epoch change waits for the database. |
| `lite.follow_interval_ms` | 1000 | ≥ both above, ≤ 60000 | Catch up at the latest after this time. |
| `lite.gate_retries` | 20 | ≥ 0 | How often to catch up again when the database shows an inconsistent state right after a catch-up. |
| `lite.max_consecutive_failures` | 30 | ≥ 1 | After this many failed catch-ups in a row, the process stops (and systemd restarts it). |
| `lite.max_pending_committed` | 50 | ≥ 1 | Above this number of vertices that consensus has committed but the database does not show yet, the lite validator does not vote. |
| `lite.malloc_trim_interval_s` | 300 | 0–86400 | Return free memory of the C allocator to the system every N seconds (0 = never). Without it, the process grows by hundreds of MB per hour. |

## Node settings that matter

| Key | Rule |
|---|---|
| `db.location` | The lite validator's own directory (`LITE_DIR/data`). Must not contain, or be contained in, the validator's database or its directory. |
| `node.key.path` | The lite validator's own keystore (`LITE_DIR/keystore.ks`). Never the validator's key. |
| `consensus.validator_address` | The lite validator's own, registered validator address. Without it, the lite validator only follows. A mistyped address or one of another network is refused at the start (exit 78). |
| `consensus.use_genesis_for_validator_address` | Refused. |
| `network.p2p.listen_port`, `api.core.port`, `api.system.port`, `api.prometheus.port`, `api.engine_state.port` | Must not be a default port of a node (30000, 3333, 3334, 3335, 3336), also not by leaving the line out. |
| `network.p2p.broadcast_port` | 30000 gives a warning only (port forwarding on the router is possible). |
| `api.core.bind_address`, `api.engine_state.bind_address` | Must be IP addresses (Rust APIs). `api.system.bind_address` and `api.prometheus.bind_address` also take host names. |
| `network.id`, genesis, seed nodes | As for the validator. A wrong network is refused at the start ("Inconsistent genesis configuration"). |

Environment (`LITE_DIR/environment`):

| Variable | Recommended |
|---|---|
| `RADIX_NODE_KEYSTORE_PASSWORD` | the keystore password |
| `JAVA_OPTS` | `-Xms256m -Xmx512m -XX:MaxDirectMemorySize=1g` |
| `MALLOC_ARENA_MAX` | set to 2 by the start script unless set; leave it |

## Exit codes

| Code | Meaning | systemd (`install.sh` unit) |
|---|---|---|
| 0 / 143 | stopped normally | – |
| 78 | A condition that a restart does not fix: a configuration error; the key is registered for another validator or for the validator whose database is read; the database shows other hashes than the lite validator's consensus committed. **Look at the journal.** | no restart |
| 255 | A condition that may pass: the database is missing or writable at the start (e.g. a file system not yet mounted at boot), too many failed catch-ups, a protocol update of the standard node, an unexpected failure. | restart after 120 s, at most 3 starts per hour |
| killed (signal) | e.g. at the memory limit of the unit (`MemoryMax`) | restart after 120 s |

## Messages worth knowing

| Message (journal) | Meaning |
|---|---|
| `Lite validator at start: … it only follows …` (INFO) | The configured validator address is registered with another key, e.g. because the operator moved the validator to another host for maintenance. The lite validator follows and takes part again by itself when the key is changed back. Reported only when this changes. |
| `Lite validator halts and must not be restarted automatically: …` (ERROR) | Exit 78, see above; the reason follows in the message. |
| `Lite validator stops: The primary node is executing a protocol update …` (ERROR) | Expected at a protocol update; systemd restarts the lite validator after 120 s. |
| `registered public key … doesn't match` (WARN) | The key of this process is not the key registered for `consensus.validator_address`. |
