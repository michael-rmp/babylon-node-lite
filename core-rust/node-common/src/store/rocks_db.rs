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

// Modified in 2026 by michael-rmp for the lite validator (babylon-node-lite), see NOTICE.

use crate::prelude::*;
use rocksdb::checkpoint::Checkpoint;
use rocksdb::{
    AsColumnFamilyRef, ColumnFamily, DBPinnableSlice, IteratorMode, Snapshot, WriteBatch, DB,
};

use std::path::PathBuf;

/// A redefined RocksDB's "key and value bytes" tuple (the original one lives in a private module).
pub type KVBytes = (Box<[u8]>, Box<[u8]>);

/// A trait capturing the common read methods present both in a "direct" RocksDB instance and in its
/// snapshots.
///
/// The library we use (a thin C wrapper, really) does not introduce this trivial and natural trait
/// itself, while we desperately need it to abstract the DB-reading code from the actual source of
/// data.
///
/// A note on changed error handling:
/// The original methods typically return [`Result`]s. Our trait assumes panics instead, since we
/// treat all database access errors as fatal anyways.
pub trait ReadableRocks {
    /// Resolves the column family by name.
    fn cf_handle(&self, name: &str) -> &ColumnFamily;

    /// Starts iteration over key-value pairs, according to the given [`IteratorMode`].
    fn iterator_cf(
        &self,
        cf: &impl AsColumnFamilyRef,
        mode: IteratorMode,
    ) -> Box<dyn Iterator<Item = KVBytes> + '_>;

    /// Gets a single value by key.
    fn get_pinned_cf(
        &self,
        cf: &impl AsColumnFamilyRef,
        key: impl AsRef<[u8]>,
    ) -> Option<DBPinnableSlice<'_>>;

    /// Gets multiple values by keys.
    ///
    /// Syntax note:
    /// The `<'a>` here is not special at all: it could technically be 100% inferred. Just the
    /// compiler feature allowing to skip it from within the `<Item = &...>` is not yet stable.
    /// TODO(when the rustc feature mentioned above becomes stable): get rid of the `<'a>`.
    fn multi_get_cf<'a>(
        &'a self,
        keys: impl IntoIterator<Item = (&'a (impl AsColumnFamilyRef + 'a), impl AsRef<[u8]>)>,
    ) -> Vec<Option<Vec<u8>>>;
}

/// A write-supporting extension of the [`ReadableRocks`].
///
/// Naturally, it is expected that only a "direct" RocksDB instance can implement this one.
pub trait WriteableRocks: ReadableRocks {
    /// Atomically writes the given batch of updates.
    fn write(&self, batch: WriteBatch);

    /// Returns a snapshot of the current state.
    fn snapshot(&self) -> SnapshotRocks<'_>;
}

/// A [`ReadableRocks`] instance opened as secondary instance.
pub trait SecondaryRocks: ReadableRocks {
    /// Tries to catch up with the primary by reading as much as possible from the
    /// log files.
    fn try_catchup_with_primary(&self);
}

/// RocksDB checkpoint support.
pub trait CheckpointableRocks {
    fn create_checkpoint(&self, checkpoint_path: PathBuf) -> Result<(), rocksdb::Error>;
}

/// Direct RocksDB instance.
pub struct DirectRocks {
    pub db: DB,
    /// Lite validator: only set for a RocksDB secondary instance (see [`Self::new_secondary()`]).
    ///
    /// A secondary does not support snapshots. Its view of the data only changes when it catches up
    /// with the primary, so a "snapshot" of a secondary is a shared lock on this view, and catching
    /// up takes it exclusively (see [`Self::lock_view_for_catch_up()`]).
    pub secondary_view: Option<parking_lot::RwLock<()>>,
    /// Lite validator: a token whose drop tells the helpers of this instance (e.g. the thread
    /// maintaining its WAL links) to stop.
    pub secondary_keep_alive: Option<std::sync::Arc<()>>,
}

impl DirectRocks {
    /// Wraps a regular (primary or read-only) RocksDB instance.
    pub fn new(db: DB) -> Self {
        Self {
            db,
            secondary_view: None,
            secondary_keep_alive: None,
        }
    }

    /// Lite validator: wraps a RocksDB secondary instance.
    pub fn new_secondary(db: DB) -> Self {
        Self {
            db,
            secondary_view: Some(parking_lot::RwLock::new(())),
            secondary_keep_alive: None,
        }
    }

    /// Lite validator: like [`Self::new_secondary()`], and keeps the given token until dropped.
    pub fn new_secondary_with_keep_alive(db: DB, keep_alive: std::sync::Arc<()>) -> Self {
        Self {
            db,
            secondary_view: Some(parking_lot::RwLock::new(())),
            secondary_keep_alive: Some(keep_alive),
        }
    }

    /// Lite validator: waits (at most for the given time) until no snapshot of this secondary
    /// instance is alive, and returns a guard which keeps new snapshots from being created. The
    /// caller catches up with the primary (and verifies the new view) while holding it.
    ///
    /// Snapshots requested while this waits are queued behind it (see [`Self::snapshot()`]), so
    /// a steady stream of readers cannot hold it off forever. `None` means that some snapshot was
    /// not released in time.
    ///
    /// Panics if this is not a secondary instance.
    pub fn lock_view_for_catch_up(
        &self,
        timeout: std::time::Duration,
    ) -> Option<parking_lot::RwLockWriteGuard<'_, ()>> {
        self.secondary_view
            .as_ref()
            .expect("lock_view_for_catch_up() is only valid on a secondary instance")
            .try_write_for(timeout)
    }

    /// Lite validator: like [`SecondaryRocks::try_catchup_with_primary()`], but returns the error.
    /// The caller is expected to hold the guard from [`Self::lock_view_for_catch_up()`].
    pub fn try_catch_up_with_primary_checked(&self) -> Result<(), rocksdb::Error> {
        self.db.try_catch_up_with_primary()
    }
}

impl ReadableRocks for DirectRocks {
    fn cf_handle(&self, name: &str) -> &ColumnFamily {
        self.db.cf_handle(name).expect(name)
    }

    fn iterator_cf(
        &self,
        cf: &impl AsColumnFamilyRef,
        mode: IteratorMode,
    ) -> Box<dyn Iterator<Item = KVBytes> + '_> {
        Box::new(
            self.db
                .iterator_cf(cf, mode)
                .map(|result| result.expect("reading from DB iterator")),
        )
    }

    fn get_pinned_cf(
        &self,
        cf: &impl AsColumnFamilyRef,
        key: impl AsRef<[u8]>,
    ) -> Option<DBPinnableSlice<'_>> {
        self.db.get_pinned_cf(cf, key).expect("DB get by key")
    }

    fn multi_get_cf<'a>(
        &'a self,
        keys: impl IntoIterator<Item = (&'a (impl AsColumnFamilyRef + 'a), impl AsRef<[u8]>)>,
    ) -> Vec<Option<Vec<u8>>> {
        self.db
            .multi_get_cf(keys)
            .into_iter()
            .map(|result| result.expect("batch DB get by key"))
            .collect()
    }
}

impl WriteableRocks for DirectRocks {
    fn write(&self, batch: WriteBatch) {
        // Lite validator: the primary's database is never written. RocksDB and the read-only mount
        // would refuse it as well; this names the cause.
        assert!(
            self.secondary_view.is_none(),
            "attempted to write to a RocksDB secondary instance (lite validator)"
        );
        self.db.write(batch).expect("DB write batch");
    }

    fn snapshot(&self) -> SnapshotRocks<'_> {
        let view = match &self.secondary_view {
            Some(lock) => SnapshotView::SecondaryView(SecondaryViewGuard::acquire(lock)),
            None => SnapshotView::Snapshot(self.db.snapshot()),
        };
        SnapshotRocks { db: &self.db, view }
    }
}

impl SecondaryRocks for DirectRocks {
    fn try_catchup_with_primary(&self) {
        self.db
            .try_catch_up_with_primary()
            .expect("secondary DB catchup");
    }
}

impl CheckpointableRocks for DirectRocks {
    fn create_checkpoint(&self, checkpoint_path: PathBuf) -> Result<(), rocksdb::Error> {
        // Lite validator: a checkpoint hard-links the primary's files.
        assert!(
            self.secondary_view.is_none(),
            "attempted to checkpoint a RocksDB secondary instance (lite validator)"
        );
        create_checkpoint(&self.db, checkpoint_path)
    }
}

impl<'db> CheckpointableRocks for SnapshotRocks<'db> {
    fn create_checkpoint(&self, checkpoint_path: PathBuf) -> Result<(), rocksdb::Error> {
        assert!(
            matches!(self.view, SnapshotView::Snapshot(_)),
            "attempted to checkpoint a RocksDB secondary instance (lite validator)"
        );
        create_checkpoint(self.db, checkpoint_path)
    }
}

fn create_checkpoint(db: &DB, checkpoint_path: PathBuf) -> Result<(), rocksdb::Error> {
    let checkpoint = Checkpoint::new(db)?;
    checkpoint.create_checkpoint(checkpoint_path)?;
    Ok(())
}

/// Snapshot of RocksDB.
///
/// Implementation note:
/// The original [`DB`] reference is interestingly kept internally by the [`Snapshot`] as well.
/// However, we need direct access to it for the [`Self::cf_handle()`] reasons.
pub struct SnapshotRocks<'db> {
    db: &'db DB,
    view: SnapshotView<'db>,
}

/// What keeps a [`SnapshotRocks`] stable.
enum SnapshotView<'db> {
    /// A real RocksDB snapshot (primary or read-only instance).
    Snapshot(Snapshot<'db>),
    /// Lite validator: a shared lock on the view of a secondary instance, which then cannot catch
    /// up while this is held. Reads go directly to the [`DB`].
    SecondaryView(#[allow(dead_code)] SecondaryViewGuard<'db>),
}

thread_local! {
    /// The view locks (identified by address) which the current thread holds shared, with the
    /// number of guards for each. In practice, at most one entry: the state database.
    static HELD_SECONDARY_VIEWS: std::cell::RefCell<Vec<(usize, usize)>> =
        const { std::cell::RefCell::new(Vec::new()) };
}

/// A shared lock on the view of a secondary instance.
///
/// A thread's first guard queues fairly behind a waiting catch-up (otherwise overlapping readers
/// could starve it). A further guard of a thread which already holds one must not wait for that
/// catch-up - which in turn waits for the thread's first guard - so it is taken recursively.
struct SecondaryViewGuard<'db> {
    #[allow(dead_code)] // only held to release the lock when dropped
    guard: parking_lot::RwLockReadGuard<'db, ()>,
    lock_address: usize,
}

impl<'db> SecondaryViewGuard<'db> {
    fn acquire(lock: &'db parking_lot::RwLock<()>) -> Self {
        let lock_address = lock as *const parking_lot::RwLock<()> as usize;
        let already_held = HELD_SECONDARY_VIEWS.with(|held| {
            held.borrow()
                .iter()
                .any(|(address, _)| *address == lock_address)
        });
        let guard = if already_held {
            lock.read_recursive()
        } else {
            lock.read()
        };
        HELD_SECONDARY_VIEWS.with(|held| {
            let mut held = held.borrow_mut();
            match held
                .iter_mut()
                .find(|(address, _)| *address == lock_address)
            {
                Some((_, count)) => *count += 1,
                None => held.push((lock_address, 1)),
            }
        });
        Self {
            guard,
            lock_address,
        }
    }
}

impl Drop for SecondaryViewGuard<'_> {
    fn drop(&mut self) {
        // Note: the underlying guard is not `Send`, so this is the acquiring thread.
        HELD_SECONDARY_VIEWS.with(|held| {
            let mut held = held.borrow_mut();
            if let Some(index) = held
                .iter()
                .position(|(address, _)| *address == self.lock_address)
            {
                held[index].1 -= 1;
                if held[index].1 == 0 {
                    held.swap_remove(index);
                }
            }
        });
    }
}

impl<'db> ReadableRocks for SnapshotRocks<'db> {
    fn cf_handle(&self, name: &str) -> &ColumnFamily {
        self.db.cf_handle(name).expect(name)
    }

    fn iterator_cf(
        &self,
        cf: &impl AsColumnFamilyRef,
        mode: IteratorMode,
    ) -> Box<dyn Iterator<Item = KVBytes> + '_> {
        match &self.view {
            SnapshotView::Snapshot(snapshot) => Box::new(
                snapshot
                    .iterator_cf(cf, mode)
                    .map(|result| result.expect("reading from snapshot DB iterator")),
            ),
            SnapshotView::SecondaryView(_) => Box::new(
                self.db
                    .iterator_cf(cf, mode)
                    .map(|result| result.expect("reading from secondary DB iterator")),
            ),
        }
    }

    fn get_pinned_cf(
        &self,
        cf: &impl AsColumnFamilyRef,
        key: impl AsRef<[u8]>,
    ) -> Option<DBPinnableSlice<'_>> {
        match &self.view {
            SnapshotView::Snapshot(snapshot) => snapshot
                .get_pinned_cf(cf, key)
                .expect("snapshot DB get by key"),
            SnapshotView::SecondaryView(_) => self
                .db
                .get_pinned_cf(cf, key)
                .expect("secondary DB get by key"),
        }
    }

    fn multi_get_cf<'a>(
        &'a self,
        keys: impl IntoIterator<Item = (&'a (impl AsColumnFamilyRef + 'a), impl AsRef<[u8]>)>,
    ) -> Vec<Option<Vec<u8>>> {
        let results = match &self.view {
            SnapshotView::Snapshot(snapshot) => snapshot.multi_get_cf(keys),
            SnapshotView::SecondaryView(_) => self.db.multi_get_cf(keys),
        };
        results
            .into_iter()
            .map(|result| result.expect("batch snapshot DB get by key"))
            .collect()
    }
}

#[cfg(test)]
mod secondary_view_tests {
    use super::*;
    use rocksdb::Options;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Arc;
    use std::time::Duration;

    fn read(rocks: &impl ReadableRocks) -> Vec<u8> {
        let cf = rocks.cf_handle("c");
        rocks.get_pinned_cf(cf, b"k").expect("key present").to_vec()
    }

    /// Lite validator: a snapshot of a secondary keeps its view stable, catching up waits for it,
    /// and a nested snapshot does not deadlock behind a waiting catch-up.
    #[test]
    fn snapshot_of_secondary_blocks_catch_up() {
        let dir = tempfile::tempdir().unwrap();
        let primary_path = dir.path().join("primary");
        let secondary_path = dir.path().join("secondary");

        let mut opts = Options::default();
        opts.create_if_missing(true);
        opts.create_missing_column_families(true);
        let primary = DB::open_cf(&opts, &primary_path, ["c"]).unwrap();
        primary
            .put_cf(primary.cf_handle("c").unwrap(), b"k", b"1")
            .unwrap();

        let secondary = Arc::new(DirectRocks::new_secondary(
            DB::open_cf_as_secondary(&Options::default(), &primary_path, &secondary_path, ["c"])
                .unwrap(),
        ));

        let snapshot = secondary.snapshot();
        assert_eq!(read(&snapshot), b"1");

        primary
            .put_cf(primary.cf_handle("c").unwrap(), b"k", b"2")
            .unwrap();

        let caught_up = Arc::new(AtomicBool::new(false));
        let catch_up = {
            let secondary = secondary.clone();
            let caught_up = caught_up.clone();
            std::thread::spawn(move || {
                let _guard = secondary
                    .lock_view_for_catch_up(Duration::from_secs(30))
                    .unwrap();
                secondary.try_catch_up_with_primary_checked().unwrap();
                caught_up.store(true, Ordering::SeqCst);
            })
        };

        // While the catch-up waits, a snapshot requested by another thread queues behind it:
        let other_reader = {
            let secondary = secondary.clone();
            std::thread::spawn(move || {
                std::thread::sleep(Duration::from_millis(100));
                read(&secondary.snapshot())
            })
        };

        std::thread::sleep(Duration::from_millis(300));
        assert!(
            !caught_up.load(Ordering::SeqCst),
            "catch-up must wait for the snapshot"
        );
        assert!(
            !other_reader.is_finished(),
            "a new reader must not overtake the waiting catch-up"
        );
        assert_eq!(
            read(&snapshot),
            b"1",
            "view must stay stable under a snapshot"
        );
        {
            // Nested snapshot while the catch-up is waiting: must not deadlock.
            let nested = secondary.snapshot();
            assert_eq!(read(&nested), b"1");
        }

        drop(snapshot);
        catch_up.join().unwrap();
        assert!(caught_up.load(Ordering::SeqCst));
        assert_eq!(other_reader.join().unwrap(), b"2");
        assert_eq!(read(&secondary.snapshot()), b"2");
        assert_eq!(read(secondary.as_ref()), b"2");
    }

    #[test]
    fn catch_up_gives_up_when_a_snapshot_is_not_released() {
        let dir = tempfile::tempdir().unwrap();
        let primary_path = dir.path().join("primary");
        let mut opts = Options::default();
        opts.create_if_missing(true);
        opts.create_missing_column_families(true);
        let _primary = DB::open_cf(&opts, &primary_path, ["c"]).unwrap();
        let secondary = DirectRocks::new_secondary(
            DB::open_cf_as_secondary(
                &Options::default(),
                &primary_path,
                &dir.path().join("secondary"),
                ["c"],
            )
            .unwrap(),
        );
        let snapshot = secondary.snapshot();
        assert!(secondary
            .lock_view_for_catch_up(Duration::from_millis(50))
            .is_none());
        drop(snapshot);
        assert!(secondary
            .lock_view_for_catch_up(Duration::from_millis(50))
            .is_some());
    }

    #[test]
    #[should_panic(expected = "attempted to write to a RocksDB secondary instance")]
    fn secondary_refuses_writes() {
        let dir = tempfile::tempdir().unwrap();
        let primary_path = dir.path().join("primary");
        let mut opts = Options::default();
        opts.create_if_missing(true);
        opts.create_missing_column_families(true);
        let _primary = DB::open_cf(&opts, &primary_path, ["c"]).unwrap();
        let secondary = DirectRocks::new_secondary(
            DB::open_cf_as_secondary(
                &Options::default(),
                &primary_path,
                &dir.path().join("secondary"),
                ["c"],
            )
            .unwrap(),
        );
        let mut batch = WriteBatch::default();
        batch.put_cf(secondary.cf_handle("c"), b"k", b"x");
        secondary.write(batch);
    }

    #[test]
    fn primary_snapshot_is_unchanged() {
        let dir = tempfile::tempdir().unwrap();
        let mut opts = Options::default();
        opts.create_if_missing(true);
        opts.create_missing_column_families(true);
        let rocks = DirectRocks::new(DB::open_cf(&opts, dir.path(), ["c"]).unwrap());
        rocks.db.put_cf(rocks.cf_handle("c"), b"k", b"1").unwrap();
        let snapshot = rocks.snapshot();
        rocks.db.put_cf(rocks.cf_handle("c"), b"k", b"2").unwrap();
        assert_eq!(read(&snapshot), b"1");
        assert_eq!(read(&rocks), b"2");
    }
}
