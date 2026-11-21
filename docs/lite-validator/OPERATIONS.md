# Operations

Examples use the unit `lite-lite-1.service` and the ports from the template (lite validator
3343–3346, validator 3333–3335).

**Stopping the lite validator is always safe:** `sudo systemctl stop lite-lite-1.service`. It
changes nothing of the standard node. When in doubt, stop the lite validator first.

## Monitoring

```bash
systemctl show lite-lite-1.service -p ActiveEnterTimestamp -p NRestarts -p MemoryCurrent
curl -s localhost:3344/system/health     # lite validator
curl -s localhost:3334/system/health     # validator: watch "missed_count"
for p in 3335 3345; do curl -s localhost:$p/prometheus/metrics | grep '^rn_ledger_state_version '; done
curl -s localhost:3345/prometheus/metrics | grep -E '^rn_lite_|^rn_ledger_lite'
sudo journalctl -u lite-lite-1.service --since today -o cat | sed 's/\x1b\[[0-9;]*m//g' | grep -E 'WARN|ERROR'
```

What to look at:

| Value | Expected | Act when |
|---|---|---|
| validator `missed_count` | unchanged by the lite validator | it rises after the lite validator started: stop the lite validator and investigate |
| lite `status` | `UP` (`SYNCING` while opening) | it stays `OUT_OF_SYNC` or the state version stays behind |
| state versions | lite = validator or a few versions behind | the gap grows |
| `NRestarts` | 0 | it rises: look at the journal (memory limit, failed catch-ups) |
| `MemoryCurrent` | below `MemoryMax` with room to spare | it approaches the limit |
| `rn_lite_catch_up_seconds` / `rn_lite_catch_ups_total` | ~0.08–0.1 s per catch-up on mainnet | much slower: I/O pressure on the host |

Metrics of the lite validator (Prometheus, port 3345):

| Metric | Meaning |
|---|---|
| `rn_lite_catch_ups_total`, `rn_lite_catch_up_seconds` | catch-ups with the validator's database and their time |
| `rn_lite_view_changes_total` | catch-ups that showed a new ledger state |
| `rn_lite_rocksdb_*` | memory of the RocksDB secondary (block cache, memtables, table readers, live versions) |
| `rn_lite_malloc_*` | memory of the C allocator (in use, free, mmapped) |
| `rn_ledger_lite_*` | consensus commits waiting for the database, prepares and votes withheld |

## Memory

Measured on mainnet (one lite validator as an active validator, `MemoryMax=4G`):

| Part | Size |
|---|---|
| Java heap | grows to `-Xmx` as a validator, but the live set after GC is small (~80 MB on mainnet, 34–71 MB under heavy load locally); `-Xmx512m` is enough |
| Native (Rust, RocksDB) | swings with the time of day, ~1.3–2.8 GB in use, no trend over days; block cache 256 MB, memtables ~240 MB, table readers ~180 MB |
| Process (RSS) | ~1.7–3.0 GB with `-Xmx512m` (peak 2.99 GB over a day on mainnet); ~2.3–3.4 GB with `-Xmx768m`; peak 3.67 GB with `-Xmx1g` |

Settings that matter: `lite.malloc_trim_interval_s=300` (default; without it, freed memory is not
returned and the process grows by hundreds of MB per hour), `MALLOC_ARENA_MAX=2` (set by the start
script), `lite.max_open_files`, `lite.block_cache_mb`.

`JAVA_OPTS` in `environment` take effect at the next start of the unit; no immediate restart is
needed.

## Maintenance of the validator host

- **Restart of the standard node:** the lite validator keeps voting until
  `lite.max_pending_committed` (50) commits wait for the database, then stops voting and continues
  when the standard node is back. Nothing to do. For a longer stop, stop the lite validator too.
- **Reboot:** with the unit enabled, it starts after `babylon-node.service`. If it starts before
  the database is mounted, it ends with 255 and is restarted after 120 s.
- **Disk:** keep enough free space on the validator's disk; the watchdog stops all lite
  validators below `MIN_FREE_GB`.

## Moving a validator with a key change

Operators move a validator to another host (e.g. for maintenance) by registering the key of the
other host for the validator, and later move it back. This is normal operation and the lite
validator handles it:

- If its configured `consensus.validator_address` is registered with another key, the lite
  validator only follows and logs once, at INFO: `… it only follows (normal while the validator
  runs elsewhere …)`.
- When the key of the lite validator is registered again, it logs `… it takes part in consensus
  whenever it is in the validator set` and takes part from that epoch on.

It stops for good (exit 78) only if **its own key** is registered for **another** validator, in
particular for the validator whose database it reads.

**One key, one process:** never let two processes run with the same key at the same time. When
moving a validator, change the registered key; do not start a second process with a copy of the
keystore.

## Protocol updates and new releases

- At a protocol update, the lite validator stops when the standard node executes the update
  (`Lite validator stops: The primary node is executing a protocol update …`, exit 255) and is
  restarted by systemd after 120 s. It is away for ~2–6 minutes.
- A new node release may come with a protocol update or a changed database format. Build a
  lite package from the matching release and update both nodes together
  ([INSTALL.md](INSTALL.md), step 7). Do not run a lite validator of an older release against a
  newer standard node; there is no automatic check of this yet.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| exit 78 right after the start | configuration error; the journal names the setting |
| `… is a default port of a node …` | give the lite validator ports of its own |
| `… writable for this process` | the lite user can write in the validator's database: fix the permissions (never start it like that) |
| `no CURRENT file` | wrong `lite.primary_state_manager_path`, or the database is not mounted yet |
| `Inconsistent genesis configuration` | network or genesis settings differ from the validator's |
| `registered for the primary's validator` / `registered for another validator` | the lite validator's key is registered for another validator: check the keystore and the registration |
| `LOCK` error at the start | a second lite validator uses the same `db.location` |
| restarts at regular intervals | memory limit reached: check `JAVA_OPTS`, `lite.malloc_trim_interval_s`, `MemoryMax` |
| status `UP` but no proposals | not in the active validator set, or the key does not match the registration (`registered public key … doesn't match`) |
