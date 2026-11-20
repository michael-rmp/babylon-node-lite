/* Copyright 2021 Radix Publishing Ltd incorporated in Jersey (Channel Islands).
 *
 * Licensed under the Radix License, Version 1.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at:
 *
 * radixfoundation.org/licenses/LICENSE-v1
 *
 * The Licensor hereby grants permission for the Canonical version of the Work to be
 * published, distributed and used under or by reference to the Licensor’s trademark
 * Radix ® and use of any unregistered trade names, logos or get-up.
 *
 * The Licensor provides the Work (and each Contributor provides its Contributions) on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE, NON-INFRINGEMENT,
 * MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * Whilst the Work is capable of being deployed, used and adopted (instantiated) to create
 * a distributed ledger it is your responsibility to test and validate the code, together
 * with all logic and performance of that code under all foreseeable scenarios.
 *
 * The Licensor does not make or purport to make and hereby excludes liability for all
 * and any representation, warranty or undertaking in any form whatsoever, whether express
 * or implied, to any entity or person, including any representation, warranty or
 * undertaking, as to the functionality security use, value or other characteristics of
 * any distributed ledger nor in respect the functioning or value of any tokens which may
 * be created stored or transferred using the Work. The Licensor does not warrant that the
 * Work or any use of the Work complies with any law or regulation in any territory where
 * it may be implemented or used or that it will be appropriate for any specific purpose.
 *
 * Neither the licensor nor any current or former employees, officers, directors, partners,
 * trustees, representatives, agents, advisors, contractors, or volunteers of the Licensor
 * shall be liable for any direct or indirect, special, incidental, consequential or other
 * losses of any kind, in tort, contract or otherwise (including but not limited to loss
 * of revenue, income or profits, or loss of use or data, or loss of reputation, or loss
 * of any economic or other opportunity of whatsoever nature or howsoever arising), arising
 * out of or in connection with (without limitation of any use, misuse, of any ledger system
 * or use made or its functionality or any performance or operation of any code or protocol
 * caused by bugs or programming or logic errors or otherwise);
 *
 * A. any offer, purchase, holding, use, sale, exchange or transmission of any
 * cryptographic keys, tokens or assets created, exchanged, stored or arising from any
 * interaction with the Work;
 *
 * B. any failure in a transmission or loss of any token or assets keys or other digital
 * artefacts due to errors in transmission;
 *
 * C. bugs, hacks, logic errors or faults in the Work or any communication;
 *
 * D. system software or apparatus including but not limited to losses caused by errors
 * in holding or transmitting tokens by any third-party;
 *
 * E. breaches or failure of security including hacker attacks, loss or disclosure of
 * password, loss of private key, unauthorised use or misuse of such passwords or keys;
 *
 * F. any losses including loss of anticipated savings or other benefits resulting from
 * use of the Work or any changes to the Work (however implemented).
 *
 * You are solely responsible for; testing, validating and evaluation of all operation
 * logic, functionality, security and appropriateness of using the Work for any commercial
 * or non-commercial purpose and for any reproduction or redistribution by You of the
 * Work. You assume all risks associated with Your use of the Work and the exercise of
 * permissions under this License.
 */

//! Lite validator: following the state database of another, running node (the "primary").
//!
//! A lite validator opens the primary's database as a RocksDB secondary instance (see
//! [`StateManager::new_lite()`]) and never writes to it. Its view of the ledger changes only inside
//! [`StateManager::lite_catch_up()`].

use crate::prelude::*;
use crate::store::wal_links::PrimaryFingerprint;
use node_common::metrics::*;
use prometheus::{Histogram, IntCounter, IntGauge};
use std::path::Path;
use std::time::{Duration, Instant};

/// Start checks of a lite validator (the layer of database protection which is in our own code).
///
/// Refuses to continue if:
/// - `primary_path` does not hold a database,
/// - this process could write to `primary_path` or to any file in it (the directory must be
///   mounted read-only or be owned by another user without write permission for us),
/// - `own_path` is inside `primary_path` or the other way round.
///
/// Nothing is created or modified at `primary_path`. `own_path` is created if missing.
/// Returns the number of checked entries of `primary_path`.
pub fn lite_start_checks(primary_path: &Path, own_path: &Path) -> Result<u64, String> {
    if !primary_path.join("CURRENT").is_file() {
        return Err(format!(
            "{} does not contain a RocksDB database (no CURRENT file)",
            primary_path.display()
        ));
    }
    if is_writable(primary_path) {
        return Err(format!(
            "the primary's database directory {} is writable for this process; it must be mounted \
             read-only or belong to another user",
            primary_path.display()
        ));
    }
    let mut checked = 0u64;
    let entries = std::fs::read_dir(primary_path)
        .map_err(|error| format!("cannot list {}: {}", primary_path.display(), error))?;
    for entry in entries {
        let entry =
            entry.map_err(|error| format!("cannot list {}: {}", primary_path.display(), error))?;
        if is_writable(&entry.path()) {
            return Err(format!(
                "{} in the primary's database directory is writable for this process",
                entry.path().display()
            ));
        }
        checked += 1;
    }

    std::fs::create_dir_all(own_path)
        .map_err(|error| format!("cannot create {}: {}", own_path.display(), error))?;
    let primary = primary_path
        .canonicalize()
        .map_err(|error| format!("{}: {}", primary_path.display(), error))?;
    let own = own_path
        .canonicalize()
        .map_err(|error| format!("{}: {}", own_path.display(), error))?;
    if own.starts_with(&primary) || primary.starts_with(&own) {
        return Err(format!(
            "the own directory {} and the primary's database directory {} must not contain each \
             other",
            own.display(),
            primary.display()
        ));
    }
    if !is_writable(&own) {
        return Err(format!(
            "the own directory {} is not writable",
            own.display()
        ));
    }
    Ok(checked)
}

/// Whether this process may write to the given path, considering both the permissions and a
/// read-only mount. Does not create or touch anything.
fn is_writable(path: &Path) -> bool {
    use std::os::unix::ffi::OsStrExt;
    let Ok(c_path) = std::ffi::CString::new(path.as_os_str().as_bytes()) else {
        return true; // cannot be checked: treat as a failed check
    };
    unsafe { libc::access(c_path.as_ptr(), libc::W_OK) == 0 }
}

/// The ledger state which a lite validator currently sees, after it was verified to be consistent.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LiteView {
    pub state_version: StateVersion,
    pub hashes: LedgerHashes,
    pub epoch: Epoch,
    /// Set if this state ends its epoch (i.e. the next transaction starts the given one).
    pub next_epoch: Option<Epoch>,
    pub proposer_timestamp_ms: i64,
    pub next_protocol_version: Option<ProtocolVersionName>,
}

#[derive(Debug, Clone, PartialEq, Eq, ScryptoSbor)]
pub enum LiteCatchUpError {
    /// RocksDB failed to catch up (e.g. the primary deleted a WAL file in the meantime).
    /// The previous view is still valid; the caller should retry later.
    CatchUpFailed(String),
    /// The view stayed inconsistent after all retries (see [`lite_consistent_view()`]).
    /// The caller must not use the database until a later catch-up succeeds.
    InconsistentView(String),
    /// The ledger went backwards, or shows different hashes at the same state version.
    /// This must never happen (e.g. the primary lost data after a power failure); the caller must
    /// stop.
    Diverged(String),
}

/// The consistency gate: the top state version must be the same in the proofs, the transactions and
/// the transaction identifiers, and the proof's hashes must be those of the top transaction.
///
/// The primary writes a commit as one atomic batch, so this is expected to always hold. It is
/// checked anyway, since everything a lite validator signs is derived from this view.
pub fn lite_consistent_view(database: &ActualStateManagerDatabase) -> Result<LiteView, String> {
    let proof = database
        .get_latest_proof()
        .ok_or_else(|| "no ledger proof".to_string())?;
    let max_transaction_version = database.max_state_version();
    let (identifiers_version, identifiers) = database
        .get_top_transaction_identifiers()
        .ok_or_else(|| "no transaction identifiers".to_string())?;
    let header = proof.ledger_header;
    if header.state_version != max_transaction_version
        || header.state_version != identifiers_version
    {
        return Err(format!(
            "top state versions differ: proof={} transactions={} identifiers={}",
            header.state_version.number(),
            max_transaction_version.number(),
            identifiers_version.number()
        ));
    }
    if header.hashes != identifiers.resultant_ledger_hashes {
        return Err(format!(
            "ledger hashes of proof and identifiers differ at state version {}",
            header.state_version.number()
        ));
    }
    Ok(LiteView {
        state_version: header.state_version,
        hashes: header.hashes,
        epoch: header.epoch,
        next_epoch: header.next_epoch.map(|next_epoch| next_epoch.epoch),
        proposer_timestamp_ms: header.proposer_timestamp_ms,
        next_protocol_version: header.next_protocol_version,
    })
}

/// When the ledger has advanced by more than this between two catch-ups, the mempool is not told
/// about the individual committed transactions (the periodic re-evaluation removes them).
const LITE_MAX_TRANSACTIONS_REPORTED_TO_MEMPOOL: u64 = 10_000;

/// How long a catch-up waits for the database snapshots to be released.
const LITE_VIEW_LOCK_TIMEOUT: Duration = Duration::from_secs(10);

/// Lite validator: what [`StateManager::lite_follow()`] remembers between its calls.
#[derive(Default)]
pub struct LiteFollowState {
    /// The view returned by the last successful catch-up.
    view: Option<LiteView>,
    /// When the last successful catch-up started, and what the primary's files looked like then.
    last_catch_up: Option<(Instant, Option<PrimaryFingerprint>)>,
    /// When the memory gauges were last updated.
    last_memory_report: Option<Instant>,
    metrics: Option<LiteFollowMetrics>,
}

/// Lite validator: how often the memory gauges are updated (see [`LiteMemoryMetrics`]).
const LITE_MEMORY_REPORT_INTERVAL: Duration = Duration::from_secs(10);

/// Lite validator: what the calls of [`StateManager::lite_follow()`] amount to. (Most calls
/// return right away; the timer around the native call cannot tell the catch-ups apart.)
struct LiteFollowMetrics {
    catch_ups: IntCounter,
    catch_up_seconds: Histogram,
    view_changes: IntCounter,
    memory: LiteMemoryMetrics,
}

/// Lite validator diagnostics: where the native memory goes (RocksDB's own accounting, and what
/// the C allocator holds). Meant to tell live memory from fragmentation.
struct LiteMemoryMetrics {
    memtables: IntGauge,
    memtables_including_pinned: IntGauge,
    table_readers: IntGauge,
    block_cache: IntGauge,
    block_cache_pinned: IntGauge,
    live_versions: IntGauge,
    snapshots: IntGauge,
    malloc_in_use: IntGauge,
    malloc_free: IntGauge,
    malloc_mmapped: IntGauge,
}

impl LiteMemoryMetrics {
    fn new(registry: &MetricRegistry) -> Self {
        let gauge =
            |name: &str, help: &str| IntGauge::with_opts(opts(name, help)).registered_at(registry);
        Self {
            memtables: gauge(
                "lite_rocksdb_memtables_bytes",
                "Sum of rocksdb.cur-size-all-mem-tables over all column families.",
            ),
            memtables_including_pinned: gauge(
                "lite_rocksdb_memtables_including_pinned_bytes",
                "Sum of rocksdb.size-all-mem-tables (including memtables still pinned by readers).",
            ),
            table_readers: gauge(
                "lite_rocksdb_table_readers_bytes",
                "Sum of rocksdb.estimate-table-readers-mem (indexes and filters outside the cache).",
            ),
            block_cache: gauge(
                "lite_rocksdb_block_cache_bytes",
                "rocksdb.block-cache-usage (shared by all column families).",
            ),
            block_cache_pinned: gauge(
                "lite_rocksdb_block_cache_pinned_bytes",
                "rocksdb.block-cache-pinned-usage (shared by all column families).",
            ),
            live_versions: gauge(
                "lite_rocksdb_live_versions",
                "Sum of rocksdb.num-live-versions over all column families.",
            ),
            snapshots: gauge(
                "lite_rocksdb_snapshots",
                "rocksdb.num-snapshots (the largest value over all column families).",
            ),
            malloc_in_use: gauge(
                "lite_malloc_in_use_bytes",
                "glibc mallinfo2: bytes allocated and in use (uordblks + hblkhd).",
            ),
            malloc_free: gauge(
                "lite_malloc_free_bytes",
                "glibc mallinfo2: free bytes held by the allocator, not returned to the system (fordblks).",
            ),
            malloc_mmapped: gauge(
                "lite_malloc_mmapped_bytes",
                "glibc mallinfo2: bytes in separately mmapped blocks (hblkhd).",
            ),
        }
    }

    fn update(&self, database: &ActualStateManagerDatabase) {
        let sum = |property: &str| database.sum_int_property_over_column_families(property) as i64;
        let max = |property: &str| database.max_int_property_over_column_families(property) as i64;
        self.memtables.set(sum("rocksdb.cur-size-all-mem-tables"));
        self.memtables_including_pinned
            .set(sum("rocksdb.size-all-mem-tables"));
        self.table_readers
            .set(sum("rocksdb.estimate-table-readers-mem"));
        self.block_cache.set(max("rocksdb.block-cache-usage"));
        self.block_cache_pinned
            .set(max("rocksdb.block-cache-pinned-usage"));
        self.live_versions.set(sum("rocksdb.num-live-versions"));
        self.snapshots.set(max("rocksdb.num-snapshots"));
        #[cfg(all(target_os = "linux", target_env = "gnu"))]
        {
            // SAFETY: mallinfo2() has no preconditions; it only reads the allocator's statistics.
            let info = unsafe { libc::mallinfo2() };
            self.malloc_in_use.set((info.uordblks + info.hblkhd) as i64);
            self.malloc_free.set(info.fordblks as i64);
            self.malloc_mmapped.set(info.hblkhd as i64);
        }
    }
}

impl LiteFollowState {
    pub fn new(registry: &MetricRegistry) -> Self {
        Self {
            metrics: Some(LiteFollowMetrics {
                catch_ups: IntCounter::with_opts(opts(
                    "lite_catch_ups_total",
                    "Count of catch-ups with the primary's database (failed ones included).",
                ))
                .registered_at(registry),
                catch_up_seconds: new_histogram(
                    opts(
                        "lite_catch_up_seconds",
                        "Duration of a catch-up with the primary's database, including the wait \
                         for database snapshots to be released.",
                    ),
                    vec![
                        0.001, 0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.15, 0.25, 0.5, 1.0, 5.0,
                    ],
                )
                .registered_at(registry),
                view_changes: IntCounter::with_opts(opts(
                    "lite_view_changes_total",
                    "Count of catch-ups which found a new top of the ledger.",
                ))
                .registered_at(registry),
                memory: LiteMemoryMetrics::new(registry),
            }),
            ..Self::default()
        }
    }
}

/// Lite validator: returns the free memory of the C allocator to the system (`malloc_trim(0)`).
///
/// A lite validator frees much more than it keeps (it re-reads the primary's WAL into memtables
/// and drops them on every flush of the primary), and glibc holds on to free memory inside its
/// arenas. Returns whether any memory was released. Does nothing on other platforms.
pub fn lite_trim_memory() -> bool {
    #[cfg(all(target_os = "linux", target_env = "gnu"))]
    {
        // SAFETY: malloc_trim() has no preconditions; it only releases unused allocator memory.
        unsafe { libc::malloc_trim(0) == 1 }
    }
    #[cfg(not(all(target_os = "linux", target_env = "gnu")))]
    {
        false
    }
}

impl StateManager {
    /// Lite validator: catches up with the primary (see [`Self::lite_catch_up()`]) - unless that
    /// can wait - and returns the top state version of the current view.
    ///
    /// A catch-up is expensive even when the primary has written nothing (see
    /// [`PrimaryChangeProbe`](crate::store::wal_links::PrimaryChangeProbe)). So this can be called
    /// often: it catches up when the primary's files have visibly changed, and otherwise only
    /// once `max_postponement` has passed since the last catch-up. (That limit also covers a
    /// change which the cheap check cannot see yet, and a lite validator without WAL links, which
    /// has no cheap check at all.)
    ///
    /// The primary's files change with more than commits (a validator also persists its vertex
    /// store with every consensus event), so on a busy primary they nearly always look changed.
    /// To bound the time spent catching up, no catch-up starts earlier than `min_interval` after
    /// the previous one.
    ///
    /// The same rules as for [`Self::lite_catch_up()`] apply to the caller.
    pub fn lite_follow(
        &self,
        state: &mut LiteFollowState,
        gate_retries: u32,
        min_interval: Duration,
        max_postponement: Duration,
    ) -> Result<StateVersion, LiteCatchUpError> {
        // Taken before the catch-up: what the primary writes during it must count as a change.
        let started_at = Instant::now();
        let fingerprint = self
            .lite_primary_change_probe
            .as_ref()
            .and_then(|probe| probe.fingerprint());
        if let (Some(view), Some((last_started_at, last_fingerprint))) =
            (&state.view, &state.last_catch_up)
        {
            let visibly_changed = fingerprint.is_some() && fingerprint != *last_fingerprint;
            let since_last = last_started_at.elapsed();
            if since_last < min_interval || (!visibly_changed && since_last < max_postponement) {
                return Ok(view.state_version);
            }
        }
        let result = self.lite_catch_up(state.view.as_ref(), gate_retries);
        if let Some(metrics) = &state.metrics {
            metrics.catch_ups.inc();
            metrics
                .catch_up_seconds
                .observe(started_at.elapsed().as_secs_f64());
            if let Ok(view) = &result {
                if state.view.as_ref().map(|previous| previous.hashes) != Some(view.hashes) {
                    metrics.view_changes.inc();
                }
            }
        }
        let view = result?;
        let state_version = view.state_version;
        state.view = Some(view);
        state.last_catch_up = Some((started_at, fingerprint));
        if let Some(metrics) = &state.metrics {
            let due = state
                .last_memory_report
                .is_none_or(|last| last.elapsed() >= LITE_MEMORY_REPORT_INTERVAL);
            if due {
                metrics.memory.update(self.database.access_direct().deref());
                state.last_memory_report = Some(Instant::now());
            }
        }
        Ok(state_version)
    }

    /// Lite validator: catches up with the primary and returns the verified new view.
    ///
    /// The view moves under the exclusive view lock: this waits for all database snapshots to be
    /// released, and no new snapshot can be created meanwhile. Afterwards, the in-memory services
    /// are brought in line with the new view. The caller must make sure that no `prepare` runs
    /// concurrently (in Java, both happen under the ledger lock) and that calls are not
    /// concurrent.
    ///
    /// The given `previous` is the view returned by the last successful call (if any); it is used
    /// to detect a ledger that went backwards.
    pub fn lite_catch_up(
        &self,
        previous: Option<&LiteView>,
        gate_retries: u32,
    ) -> Result<LiteView, LiteCatchUpError> {
        let database = self.database.access_direct();
        let view = {
            let Some(_view_guard) = database.lock_view_for_catch_up(LITE_VIEW_LOCK_TIMEOUT) else {
                return Err(LiteCatchUpError::CatchUpFailed(format!(
                    "database snapshots were not released within {:?}",
                    LITE_VIEW_LOCK_TIMEOUT
                )));
            };
            Self::lite_move_view(database.deref(), previous, gate_retries)?
        };
        if previous.map(|previous| previous.hashes) != Some(view.hashes) {
            self.lite_handle_new_view(database.deref(), previous, &view);
        }
        Ok(view)
    }

    /// Catches up and verifies the result. To be called under the exclusive view lock.
    fn lite_move_view(
        database: &ActualStateManagerDatabase,
        previous: Option<&LiteView>,
        gate_retries: u32,
    ) -> Result<LiteView, LiteCatchUpError> {
        let mut last_gate_failure = String::new();
        for _ in 0..=gate_retries {
            database
                .catch_up_with_primary()
                .map_err(|error| LiteCatchUpError::CatchUpFailed(error.to_string()))?;
            match lite_consistent_view(database) {
                Ok(view) => {
                    if let Some(previous) = previous {
                        if view.state_version < previous.state_version {
                            return Err(LiteCatchUpError::Diverged(format!(
                                "state version went from {} back to {}",
                                previous.state_version.number(),
                                view.state_version.number()
                            )));
                        }
                        if view.state_version == previous.state_version
                            && view.hashes != previous.hashes
                        {
                            return Err(LiteCatchUpError::Diverged(format!(
                                "different ledger hashes at the same state version {}",
                                view.state_version.number()
                            )));
                        }
                    }
                    return Ok(view);
                }
                Err(reason) => {
                    last_gate_failure = reason;
                    // E.g. the link to a WAL file which the primary has just started is not there
                    // yet: give it a moment.
                    std::thread::sleep(Duration::from_millis(2));
                }
            }
        }
        Err(LiteCatchUpError::InconsistentView(last_gate_failure))
    }

    /// Brings the in-memory services in line with a new database view. A regular node does all of
    /// this in its committer, as a by-product of executing the commit.
    ///
    /// This runs without the view lock (the view cannot move: catch-ups are not concurrent), so
    /// other readers of the database are not held up.
    fn lite_handle_new_view(
        &self,
        database: &ActualStateManagerDatabase,
        previous: Option<&LiteView>,
        view: &LiteView,
    ) {
        // The execution cache's base must be the database's top. What this node has already
        // executed on top of that state (while preparing vertices) stays usable.
        self.execution_cache_manager
            .rebase(&view.hashes.transaction_root);

        // The epoch which has started since the previous view, if any: either the new view ends
        // its epoch, or the catch-up went past an epoch end which no earlier view has shown.
        let started_epoch = match (previous, view.next_epoch) {
            (_, Some(next_epoch)) => Some(next_epoch),
            (Some(previous), None)
                if previous.epoch != view.epoch && previous.next_epoch != Some(view.epoch) =>
            {
                Some(view.epoch)
            }
            _ => None,
        };

        // Protocol update readiness is evaluated at epoch changes, and only pending updates can
        // change the protocol state. Deriving it from the database reads the history of the
        // readiness signals, so it is skipped whenever nothing can have changed.
        if started_epoch.is_some()
            && !self
                .protocol_manager
                .current_protocol_state()
                .pending_protocol_updates
                .is_empty()
        {
            self.protocol_manager
                .replace_protocol_state((self.lite_protocol_state_recompute)());
        }

        if let Some(previous) = previous {
            let first = previous.state_version.number() + 1;
            let last = view.state_version.number();
            let committed_intent_hashes = if last + 1 - first
                <= LITE_MAX_TRANSACTIONS_REPORTED_TO_MEMPOOL
            {
                (first..=last)
                    .filter_map(|version| {
                        database.get_committed_transaction_identifiers(StateVersion::of(version))
                    })
                    .filter_map(|identifiers| {
                        identifiers
                            .transaction_hashes
                            .as_user()
                            .map(|user_hashes| user_hashes.transaction_intent_hash)
                    })
                    .collect()
            } else {
                vec![]
            };
            self.mempool_manager
                .handle_externally_committed_transactions(started_epoch, committed_intent_hashes);
        }

        // The health status of the System API is derived from these. The per-validator round
        // counters and per-transaction figures come from executing a commit, so they are not
        // available here.
        self.ledger_metrics.update(
            view.state_version,
            vec![],
            view.proposer_timestamp_ms,
            None,
            vec![],
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::rocks_db::SecondaryDatabaseOptions;
    use crate::test::*;
    use node_common::scheduler::Scheduler;

    fn test_database_options() -> SecondaryDatabaseOptions {
        SecondaryDatabaseOptions {
            max_open_files: -1,
            block_cache_bytes: 8 * 1024 * 1024,
            cache_index_and_filter_blocks: false,
            wal_links: true,
        }
    }

    fn new_lite(primary_path: &std::path::Path, secondary_path: &std::path::Path) -> StateManager {
        new_lite_with(primary_path, secondary_path, test_database_options())
    }

    fn new_lite_with(
        primary_path: &std::path::Path,
        secondary_path: &std::path::Path,
        database_options: SecondaryDatabaseOptions,
    ) -> StateManager {
        let genesis_data = JavaGenesisData::new_from(BabylonSettings::test_default(), vec![]);
        StateManager::new_lite(
            StateManagerConfig::new_for_testing(primary_path.to_str().unwrap()),
            secondary_path.to_path_buf(),
            database_options,
            None,
            Arc::new(FixedGenesisDataResolver::new(genesis_data)),
            &LockFactory::new("lite"),
            &MetricRegistry::new(),
            &Scheduler::new("lite"),
        )
        .unwrap()
    }

    /// Prepares (without committing) the next round update on top of the given node's ledger.
    fn prepare_next_round_update(state_manager: &StateManager) -> PrepareResult {
        let database = state_manager.database.snapshot();
        let latest_proof = database.get_latest_proof().unwrap();
        let latest_epoch_proof = database.get_latest_epoch_proof().unwrap();
        let (_, top_identifiers) = database.get_top_transaction_identifiers().unwrap();
        let proposer_address = latest_epoch_proof
            .ledger_header
            .next_epoch
            .as_ref()
            .unwrap()
            .validator_set
            .first()
            .unwrap()
            .address;
        let (round, epoch) = match &latest_proof.ledger_header.next_epoch {
            Some(next_epoch) => (Round::of(1), next_epoch.epoch),
            None => (
                Round::of(latest_proof.ledger_header.round.number() + 1),
                latest_proof.ledger_header.epoch,
            ),
        };
        drop(database);
        state_manager.preparator.prepare(PrepareRequest {
            committed_ledger_hashes: top_identifiers.resultant_ledger_hashes,
            ancestor_transactions: vec![],
            ancestor_ledger_hashes: top_identifiers.resultant_ledger_hashes,
            proposed_transactions: vec![],
            round_history: RoundHistory {
                is_fallback: false,
                epoch,
                round,
                gap_round_leader_addresses: vec![],
                proposer_address,
                proposer_timestamp_ms: latest_proof.ledger_header.proposer_timestamp_ms,
            },
        })
    }

    fn top_of(state_manager: &StateManager) -> LiteView {
        lite_consistent_view(state_manager.database.access_direct().deref()).unwrap()
    }

    #[test]
    fn lite_follows_primary_and_prepares_the_same_hashes() {
        let dir = tempfile::tempdir().unwrap();
        let primary_path = dir.path().join("primary");
        let secondary_path = dir.path().join("secondary");

        let primary = create_bootstrapped_state_manager(
            StateManagerConfig::new_for_testing(primary_path.to_str().unwrap()),
            BabylonSettings::test_default(),
        );
        for _ in 0..3 {
            prepare_and_commit_round_update(&primary);
        }

        let lite = new_lite(&primary_path, &secondary_path);
        let view = lite.lite_catch_up(None, 0).unwrap();
        assert_eq!(view, top_of(&primary));
        assert_eq!(
            prepare_next_round_update(&lite).ledger_hashes,
            prepare_next_round_update(&primary).ledger_hashes
        );

        // The primary progresses; the lite's view only changes when it catches up:
        for _ in 0..5 {
            prepare_and_commit_round_update(&primary);
        }
        assert_eq!(top_of(&lite), view);
        assert_ne!(top_of(&primary), view);

        let new_view = lite.lite_catch_up(Some(&view), 0).unwrap();
        assert_eq!(new_view, top_of(&primary));
        assert_eq!(
            new_view.state_version.number(),
            view.state_version.number() + 5
        );
        assert_eq!(
            prepare_next_round_update(&lite).ledger_hashes,
            prepare_next_round_update(&primary).ledger_hashes
        );

        // Catching up without progress keeps the view:
        assert_eq!(lite.lite_catch_up(Some(&new_view), 0).unwrap(), new_view);

        // The lite has prepared the next round update (above). When the primary commits exactly
        // that, the lite's execution cache is moved forward instead of being thrown away...
        prepare_and_commit_round_update(&primary);
        let committed_view = top_of(&primary);
        assert!(lite
            .execution_cache_manager
            .rebase(&committed_view.hashes.transaction_root));
        // ... while an unknown state makes it start from scratch:
        let unknown_root = TransactionTreeHash::from(Hash([9; 32]));
        assert!(!lite.execution_cache_manager.rebase(&unknown_root));
        // (The catch-up below puts the base where the database is.)
        let new_view = lite.lite_catch_up(Some(&new_view), 0).unwrap();
        assert_eq!(new_view, committed_view);
        assert_eq!(
            prepare_next_round_update(&lite).ledger_hashes,
            prepare_next_round_update(&primary).ledger_hashes
        );

        // Over epoch changes, the lite's protocol state is the one the primary has tracked:
        commit_round_updates_until_epoch(&primary, Epoch::of(new_view.epoch.number() + 2));
        let later_view = lite.lite_catch_up(Some(&new_view), 0).unwrap();
        assert_eq!(later_view, top_of(&primary));
        assert!(later_view.epoch.number() > new_view.epoch.number());
        assert_eq!(
            lite.protocol_manager.current_protocol_state(),
            primary.protocol_manager.current_protocol_state()
        );
        assert_eq!(
            prepare_next_round_update(&lite).ledger_hashes,
            prepare_next_round_update(&primary).ledger_hashes
        );
    }

    #[test]
    fn lite_postpones_a_catch_up_only_while_the_primary_looks_unchanged() {
        let dir = tempfile::tempdir().unwrap();
        let primary_path = dir.path().join("primary");
        let primary = create_bootstrapped_state_manager(
            StateManagerConfig::new_for_testing(primary_path.to_str().unwrap()),
            BabylonSettings::test_default(),
        );
        prepare_and_commit_round_update(&primary);
        let never = Duration::ZERO;
        let long = Duration::from_secs(3600);

        // With WAL links, a write of the primary is noticed right away:
        let lite = new_lite(&primary_path, &dir.path().join("with_links"));
        let mut state = LiteFollowState::default();
        let start = top_of(&primary).state_version;
        assert_eq!(lite.lite_follow(&mut state, 0, never, long), Ok(start));
        assert_eq!(lite.lite_follow(&mut state, 0, never, long), Ok(start));
        let unchanged_since = state.last_catch_up.clone().unwrap().0;
        prepare_and_commit_round_update(&primary);
        let next = top_of(&primary).state_version;
        assert_ne!(next, start);
        assert_eq!(lite.lite_follow(&mut state, 0, never, long), Ok(next));
        assert_eq!(top_of(&lite).state_version, next);
        // ... and nothing was caught up in between:
        assert!(state.last_catch_up.clone().unwrap().0 > unchanged_since);
        let caught_up_at = state.last_catch_up.clone().unwrap().0;
        assert_eq!(lite.lite_follow(&mut state, 0, never, long), Ok(next));
        assert_eq!(state.last_catch_up.clone().unwrap().0, caught_up_at);
        // Too soon after the last catch-up, even a visible change has to wait:
        prepare_and_commit_round_update(&primary);
        assert_eq!(lite.lite_follow(&mut state, 0, long, long), Ok(next));
        let next = top_of(&primary).state_version;
        assert_eq!(lite.lite_follow(&mut state, 0, never, long), Ok(next));
        let caught_up_at = state.last_catch_up.clone().unwrap().0;
        // Without postponement, every call catches up:
        assert_eq!(lite.lite_follow(&mut state, 0, never, never), Ok(next));
        assert!(state.last_catch_up.clone().unwrap().0 > caught_up_at);

        // Without WAL links there is no cheap check, only the time limit:
        let lite = new_lite_with(
            &primary_path,
            &dir.path().join("without_links"),
            SecondaryDatabaseOptions {
                wal_links: false,
                ..test_database_options()
            },
        );
        let mut state = LiteFollowState::default();
        assert_eq!(lite.lite_follow(&mut state, 0, never, long), Ok(next));
        prepare_and_commit_round_update(&primary);
        assert_eq!(lite.lite_follow(&mut state, 0, never, long), Ok(next));
        assert_eq!(
            lite.lite_follow(&mut state, 0, never, never),
            Ok(top_of(&primary).state_version)
        );
    }

    #[test]
    fn lite_detects_a_ledger_that_went_backwards() {
        let dir = tempfile::tempdir().unwrap();
        let primary_path = dir.path().join("primary");
        let primary = create_bootstrapped_state_manager(
            StateManagerConfig::new_for_testing(primary_path.to_str().unwrap()),
            BabylonSettings::test_default(),
        );
        let lite = new_lite(&primary_path, &dir.path().join("secondary"));
        let view = lite.lite_catch_up(None, 0).unwrap();

        let mut ahead = view.clone();
        ahead.state_version = view.state_version.next().unwrap();
        assert!(matches!(
            lite.lite_catch_up(Some(&ahead), 0),
            Err(LiteCatchUpError::Diverged(_))
        ));

        let mut forked = view.clone();
        forked.hashes.state_root = StateHash::from(Hash([7; 32]));
        assert!(matches!(
            lite.lite_catch_up(Some(&forked), 0),
            Err(LiteCatchUpError::Diverged(_))
        ));
        drop(primary);
    }

    #[test]
    fn start_checks_refuse_a_writable_primary() {
        let dir = tempfile::tempdir().unwrap();
        let primary_path = dir.path().join("primary");
        let own_path = dir.path().join("own");
        std::fs::create_dir(&primary_path).unwrap();

        // No database:
        assert!(lite_start_checks(&primary_path, &own_path)
            .unwrap_err()
            .contains("no CURRENT file"));
        assert!(!own_path.exists());

        // A database we could write to (as in this test, owned by ourselves):
        std::fs::write(primary_path.join("CURRENT"), "x").unwrap();
        assert!(lite_start_checks(&primary_path, &own_path)
            .unwrap_err()
            .contains("is writable for this process"));
        assert!(!own_path.exists());
    }

    #[test]
    fn lite_refuses_a_missing_database() {
        let dir = tempfile::tempdir().unwrap();
        let genesis_data = JavaGenesisData::new_from(BabylonSettings::test_default(), vec![]);
        let result = StateManager::new_lite(
            StateManagerConfig::new_for_testing(dir.path().join("nothing").to_str().unwrap()),
            dir.path().join("secondary"),
            test_database_options(),
            None,
            Arc::new(FixedGenesisDataResolver::new(genesis_data)),
            &LockFactory::new("lite"),
            &MetricRegistry::new(),
            &Scheduler::new("lite"),
        );
        assert!(result.is_err());
        assert!(
            !dir.path().join("nothing").exists(),
            "must not create the primary"
        );
    }
}
