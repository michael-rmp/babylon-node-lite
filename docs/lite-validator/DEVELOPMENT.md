# Development

The changes against the Babylon node `v1.4.0.0` are in the commits on top of that tag:
`git diff v1.4.0.0 -- core core-rust core-rust-bridge common`. Changes to shared code are neutral
for a standard node (visibility, generic types, interfaces, hooks that are empty in a standard
node). The list of changed upstream files is in [NOTICE](../../NOTICE).

## Code overview

| Place | Content |
|---|---|
| `core-rust/state-manager/src/store/rocks_db.rs` | opening the validator's database as a RocksDB secondary instance, its options |
| `core-rust/node-common/src/store/rocks_db.rs` | view lock of the secondary (reads under a shared lock, catch-up under an exclusive one; secondaries have no snapshots), write block |
| `core-rust/state-manager/src/store/wal_links.rs` | links to the validator's WAL files (inotify), cheap check whether the validator has written |
| `core-rust/state-manager/src/lite.rs` | start checks, catch-up, consistency gate, following (`lite_follow`), updating caches and mempool after a catch-up |
| `core-rust/state-manager/src/jni/lite.rs`, `jni/node_rust_environment.rs` | JNI entry points |
| `core-rust-bridge/…/lite/RustLiteFollower.java` | Java side of the follower |
| `core/…/lite/LiteValidatorApplication.java` | entry point of the package; configuration checks |
| `core/…/lite/LiteConfig.java` | `lite.*` settings |
| `core/…/lite/LiteFollowerModule.java`, `LiteFollowerRunner.java`, `LiteLedgerFollower.java` | following the database, start check of the identity |
| `core/…/lite/LiteStateManagerOverrideModule.java`, `LiteStateComputer.java`, `LiteDispatcherOverrideModule.java`, `LiteVertexStoreFile.java` | the Guice composition of a lite validator: no execution of commits, no ledger sync offered, own vertex store |
| `core/…/lite/LitePendingConsensusCommits.java`, `core/…/ledger/StateComputerLedger.java` | consensus commits that wait for the database, comparison of hashes, limit |
| `core/…/lite/LiteIdentityGuard.java` | identity checks against the validator set (at the start and at every epoch change) |
| `core/…/lite/LiteStartEpochVoteWithholding.java`, `core/…/consensus/liveness/Pacemaker.java` | no vote in the start epoch |
| `core/…/lite/LiteMemoryTrimmer.java` | periodic `malloc_trim` |
| `core/…/RadixNodeApplication.java`, `RadixNodeModule.java` | a standard node refuses a lite configuration; a lite node never gets the genesis validator identity |
| `docs/lite-validator/ops/` | package script, `install.sh`, watchdog |

Rules for changes (they protect the validator's database): never open the validator's database as
a primary or with RocksDB's read-only mode; do not weaken the write block, the start checks or the
identity guard; do not patch RocksDB.

## Building and testing

```bash
. "$HOME/.cargo/env"
(cd core-rust && cargo test -p state-manager lite && cargo test -p state-manager wal_links)
./gradlew spotlessApply
./gradlew :core:test --tests '*Lite*'
(cd core-rust && cargo test)                                          # all Rust tests
./gradlew test                                                        # all Java tests
SKIP_NATIVE_RUST_BUILD=TRUE ./gradlew :core:runAllIntegrationTests    # ~40 min, needs RAM
docs/lite-validator/ops/build-lite-package.sh ../release              # the package
```

The full Rust build and the integration tests need a lot of memory; on a machine with 16 GB do
not run anything else at the same time.

## A local lite validator

Next to a local node (`./gradlew :core:run`, see the main [README](../../README.md)): mount the
node's state database read-only in a mount namespace of your own (no root needed) and start the
lite validator in it, with the configuration
[`dev/lite-local.config.example`](dev/lite-local.config.example) (`LOCAL_DIR` replaced):

```bash
mkdir -p LOCAL_DIR/primary/state_manager LOCAL_DIR/db
export RADIXDLT_HOST_IP_ADDRESS=127.0.0.1 MALLOC_ARENA_MAX=2
export RADIXDLT_NODE_KEY=<a test key of its own, base64>
unshare -rm sh -c '
  mount --bind "$1" "$2" && mount -o remount,bind,ro "$2" || exit 9
  shift 2; exec "$@"
' sh core/RADIXDB/state_manager LOCAL_DIR/primary/state_manager \
  java -Djava.library.path=core-rust/target/debug --enable-preview -Xmx768m \
  -cp "<the classpath of :core>" com.radixdlt.lite.LiteValidatorApplication -config LOCAL_DIR/lite.config
```

The lite validator refuses to start if the database is writable for it, so the read-only mount is
not optional. For local networks with several validators, give every node its own key, ports and
directory; the lite validator reads the database of one of them.
