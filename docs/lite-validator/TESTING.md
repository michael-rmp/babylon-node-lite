# Testing

What was tested, and how. Dates are November 2026.

## Automated tests

On the published code (build machine, release and debug builds):

| Suite | Command | Result |
|---|---|---|
| Rust | `cd core-rust && cargo test` | 100 tests, 0 failures |
| Java | `./gradlew test` | 902 tests (2 skipped), 0 failures |
| Integration | `SKIP_NATIVE_RUST_BUILD=TRUE ./gradlew :core:runAllIntegrationTests` | 116 tests, 0 failures (~40 min) |

Tests specific to the lite validator include: start checks and configuration
(`LiteValidatorApplicationTest`), identity (`LiteIdentityGuardTest`, `LiteNodeIdentityTest`),
following the database (`LiteLedgerFollowerTest`, `LiteFollowerRunnerTest`,
`LiteLedgerFollowerUrgencyTest`, `LiteNodeRustEnvironmentTest`), consensus commits that wait for
the database (`StateComputerLedgerLiteTest`, `LitePendingConsensusCommitsTest`), vote withholding
(`PacemakerVoteWithholdingTest`, `LiteStartEpochVoteWithholdingTest`) and memory trimming
(`LiteMemoryTrimmerTest`); in Rust the consistency gate, the secondary view lock and the WAL links
(`cargo test -p state-manager lite`, `cargo test -p state-manager wal_links`).

## Local networks

Real processes on one machine: 4 or 5 validators with equal stake; one of them is a lite
validator that reads the database of another one through a read-only bind mount. Local networks
are much faster than mainnet (up to ~80–150 rounds per second, an epoch change every few seconds),
so this is a stress test, not a model of mainnet.

| Scenario | Result |
|---|---|
| lite validator takes part (4 and 5 validators) | proposes and votes; `missed_count` 0 for all |
| lite validator with the key of the validator whose database it reads | start refused, exit 78 |
| standard node (whose database is read) stopped for 4 min | network continues with the lite validator's votes until 50 commits wait, then the lite validator stops voting; after the restart everything continues, the waiting commits match the database |
| restart and `kill -9` of the lite validator | comes back, does not vote in its start epoch, votes from the next epoch on; no double vote |
| lite validator starts while the network is stuck without it (4 validators) | the network continues at once (the lite validator executes and proposes in its start epoch) |
| protocol update (`eagle-ray` at a fixed epoch) during operation | the standard nodes execute it; the lite validator stops as designed and, after the restart, continues on the new protocol version |

In all logs: 0 × `panicked`, `Uncaught exception`, `Byzantine Behavior detected`. One defect was
found this way during development (a vertex counted twice after a BFT rebuild); the safety checks
stopped the process before it voted on a wrong state. It is fixed and covered by tests.

Found and accepted: at the very high round rates of local networks, the lite validator often hits
`lite.max_pending_committed` and proposes less than the others. On mainnet, with rounds in the
range of seconds, 50 vertices are well over half a minute of lag.

## Misconfigurations

Each case started the packaged `bin/lite-validator` with a deliberately wrong configuration.
Cases that touch a database ran against a **stopped copy** of a validator database; a fingerprint
of all its files (names, sizes, timestamps, permissions, SHA-256 of the contents) was taken before
and after each case. **It never changed: no case wrote to the validator's database.**

| Case | Result |
|---|---|
| `lite.primary_state_manager_path` missing, pointing to the parent directory, or containing / contained in `db.location`; `db.location` missing | refused, exit 78 |
| database missing, writable for the process, reached through a symlink and writable, `db.location` not writable | refused, exit 255 (may be transient at boot) |
| wrong network, missing genesis | refused |
| own address = validator's address, `lite.primary_validator_address` missing, `consensus.use_genesis_for_validator_address=true` | refused, exit 78 |
| validator address mistyped (checksum), of another network (e.g. Stokenet in a mainnet configuration), or `network.id` missing | refused at the start, exit 78, the message names the setting; addresses of the configured network (also Stokenet with `network.id=2`) are accepted |
| key of the validator, or address of a foreign validator | refused, exit 78, no restart |
| own address registered with another key | runs as a follower, INFO message once (see [OPERATIONS.md](OPERATIONS.md)) |
| ports of the running validator, or default ports (port lines forgotten) | refused before binding, exit 78 |
| default ports, lite validator started **before** the validator | refused, no port taken; the validator starts normally |
| `api.core.bind_address=localhost` | refused, exit 78 (the Rust API would not start) |
| `network.p2p.broadcast_port=30000` | runs, WARN |
| number as text, value out of range, typo in a `lite.*` key | refused, exit 78, the message names key, value or valid keys |
| second lite validator with the same `db.location` | refused (RocksDB `LOCK`), the first one continues |
| `install.sh`: wrong `node.key.path` or `db.location`, no database, keystore missing, own address = validator's address | refused, nothing created |
| two processes with the same key | **both run; not detectable.** Hence the rule "one key, one process" |

## Mainnet

One lite validator next to a standard validator on one host since mid-November 2026, first as a
follower, then as a registered validator. The host: Ubuntu 22.04.5 LTS, kernel 5.15, glibc 2.35,
OpenJDK 17.0.21, the validator's database on ZFS 2.1.5 (8 cores, 31 GB RAM); Babylon node
`v1.4.0.0` as a systemd service. Other distributions and file systems (ext4, XFS) are not tested.
Measured there:

| Value | Result |
|---|---|
| opening the database (~47 000 SST files) | ~4–5 min until `UP` |
| catch-up | 77–100 ms each, ~80–120 per minute (`lite.min_follow_interval_ms=500`) |
| state version | equal to the validator's in nearly all samples, otherwise a few versions behind |
| validator `missed_count` | 0 throughout |
| memory | see [OPERATIONS.md](OPERATIONS.md#memory); without `malloc_trim` the process reached a 4 GB limit after ~15 h, with it the native part stays without trend |

## Not tested

- Stokenet.
- Read errors on SST files that the validator deleted while they were read (only with
  `lite.max_open_files` ≠ -1; such an error stops the lite validator, systemd restarts it; not
  observed on mainnet).
- A mismatch between the database and the lite validator's consensus in a real network (exit 78;
  covered by unit tests only).
- A standard node of a newer release than the lite validator.
- Nodes running in Docker.
