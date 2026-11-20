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

//! Lite validator: a directory of symbolic links to the WAL files of another node's database.
//!
//! On every catch-up, a RocksDB secondary lists its WAL directory to discover new WAL files. By
//! default that is the database directory itself, which on mainnet holds tens of thousands of
//! files - listing it dominates the catch-up (~100 ms there, ~22 ms for 50 000 files on a local
//! disk, against less than 1 ms for the rest).
//!
//! So the secondary gets a WAL directory of its own (`wal_dir`), holding nothing but links to the
//! primary's WAL files. A background thread keeps the links up to date: it learns about created
//! and deleted WAL files from inotify, and re-lists the primary's directory now and then in case
//! an event was missed. Nothing is ever created or changed in the primary's directory.
//!
//! A link which is not there yet only means that the secondary does not see the newest writes
//! until a later catch-up - the same as catching up a moment earlier.

use std::ffi::{CString, OsStr, OsString};
use std::os::unix::ffi::{OsStrExt, OsStringExt};
use std::path::{Path, PathBuf};
use std::sync::Weak;
use std::time::{Duration, Instant};

/// How often the primary's directory is listed in full, as a safety net for missed events.
const FULL_SYNC_INTERVAL: Duration = Duration::from_secs(30);
/// The same, when inotify is not available.
const FULL_SYNC_INTERVAL_WITHOUT_INOTIFY: Duration = Duration::from_millis(500);
/// How long the watcher blocks before it checks whether it should stop.
const POLL_TIMEOUT_MS: i32 = 1000;

#[derive(Debug, Clone)]
pub struct WalLinkDirectory {
    primary_path: PathBuf,
    links_path: PathBuf,
}

impl WalLinkDirectory {
    /// Creates (or empties) the given directory and fills it with links to the current WAL files
    /// of the database at `primary_path`.
    ///
    /// Refuses a directory which holds anything but symbolic links: this only ever deletes links
    /// of its own, never a file (in case the path is misconfigured).
    pub fn prepare(primary_path: &Path, links_path: &Path) -> Result<Self, String> {
        std::fs::create_dir_all(links_path)
            .map_err(|error| format!("cannot create {}: {}", links_path.display(), error))?;
        let stale = std::fs::read_dir(links_path)
            .map_err(|error| format!("cannot list {}: {}", links_path.display(), error))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|error| format!("cannot list {}: {}", links_path.display(), error))?;
        if let Some(entry) = stale.iter().find(|entry| !is_symlink(&entry.path())) {
            return Err(format!(
                "{} holds {}, which is not a symbolic link; this directory must hold nothing but \
                 the links of a lite validator",
                links_path.display(),
                entry.file_name().to_string_lossy()
            ));
        }
        for entry in stale {
            let _ = std::fs::remove_file(entry.path());
        }
        let directory = Self {
            primary_path: primary_path.to_path_buf(),
            links_path: links_path.to_path_buf(),
        };
        directory.sync()?;
        Ok(directory)
    }

    pub fn links_path(&self) -> &Path {
        &self.links_path
    }

    /// Lists the primary's directory and makes the links match its WAL files.
    pub fn sync(&self) -> Result<(), String> {
        let mut wal_names = std::collections::HashSet::new();
        let entries = std::fs::read_dir(&self.primary_path)
            .map_err(|error| format!("cannot list {}: {}", self.primary_path.display(), error))?;
        for entry in entries.flatten() {
            let name = entry.file_name();
            if is_wal_file_name(&name) {
                self.add(&name);
                wal_names.insert(name);
            }
        }
        if let Ok(links) = std::fs::read_dir(&self.links_path) {
            for link in links.flatten() {
                if !wal_names.contains(&link.file_name()) {
                    self.remove(&link.file_name());
                }
            }
        }
        Ok(())
    }

    fn add(&self, name: &OsStr) {
        let link = self.links_path.join(name);
        if std::fs::symlink_metadata(&link).is_err() {
            // A failure (e.g. a concurrent creation) is repaired by the next full sync.
            let _ = std::os::unix::fs::symlink(self.primary_path.join(name), &link);
        }
    }

    /// Removes the link of the given name (and nothing which is not a link).
    fn remove(&self, name: &OsStr) {
        let link = self.links_path.join(name);
        if is_symlink(&link) {
            let _ = std::fs::remove_file(link);
        }
    }

    /// Starts the thread which keeps the links up to date. It stops soon after the given token
    /// is dropped.
    pub fn spawn_watcher(self, keep_alive: Weak<()>) {
        let spawned = std::thread::Builder::new()
            .name("lite-wal-links".to_string())
            .spawn(move || self.watch(keep_alive));
        if let Err(error) = spawned {
            // Without the thread, the secondary would never see a new WAL file.
            panic!("cannot start the WAL link watcher: {}", error);
        }
    }

    fn watch(self, keep_alive: Weak<()>) {
        let watch = InotifyWatch::new(&self.primary_path);
        if watch.is_none() {
            tracing::warn!(
                "inotify is not available for {}; listing it every {:?} instead",
                self.primary_path.display(),
                FULL_SYNC_INTERVAL_WITHOUT_INOTIFY
            );
        }
        let mut last_full_sync = Instant::now();
        while keep_alive.upgrade().is_some() {
            let full_sync_interval = match &watch {
                Some(watch) => {
                    match watch.wait_for_events(POLL_TIMEOUT_MS) {
                        WatchEvents::Changes(changes) => {
                            for change in changes {
                                match change {
                                    WalChange::Created(name) => self.add(&name),
                                    WalChange::Deleted(name) => self.remove(&name),
                                }
                            }
                        }
                        // Events were lost (or the watch broke): look at everything, now.
                        WatchEvents::Unknown => {
                            last_full_sync = Instant::now() - FULL_SYNC_INTERVAL
                        }
                    }
                    FULL_SYNC_INTERVAL
                }
                None => {
                    std::thread::sleep(FULL_SYNC_INTERVAL_WITHOUT_INOTIFY);
                    FULL_SYNC_INTERVAL_WITHOUT_INOTIFY
                }
            };
            if last_full_sync.elapsed() >= full_sync_interval {
                if let Err(error) = self.sync() {
                    tracing::warn!("cannot update the WAL links: {}", error);
                }
                last_full_sync = Instant::now();
            }
        }
    }
}

/// Lite validator: tells cheaply whether the primary has written anything since an earlier look.
///
/// A catch-up of a RocksDB secondary costs time in proportion to the number of SST files of the
/// database, even when there is nothing new (tens of milliseconds on mainnet). Whatever the
/// primary does shows in one of these: its newest WAL file grows, a new WAL file appears, or its
/// MANIFEST grows or is replaced. Looking at them takes a few system calls.
///
/// The WAL files are found via the link directory, which trails the primary by a moment (or, if
/// an inotify event got lost, until the next full sync). So an unchanged [`PrimaryFingerprint`]
/// allows postponing a catch-up, but not skipping it forever.
#[derive(Debug, Clone)]
pub struct PrimaryChangeProbe {
    primary_path: PathBuf,
    links_path: PathBuf,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PrimaryFingerprint {
    wal_file_count: usize,
    newest_wal_file: OsString,
    newest_wal_file_size: u64,
    manifest_file: Vec<u8>,
    manifest_file_size: u64,
}

impl PrimaryChangeProbe {
    pub fn new(primary_path: &Path, links_path: &Path) -> Self {
        Self {
            primary_path: primary_path.to_path_buf(),
            links_path: links_path.to_path_buf(),
        }
    }

    /// Returns `None` if the current state cannot be told (which the caller must treat like "it
    /// may have changed").
    pub fn fingerprint(&self) -> Option<PrimaryFingerprint> {
        let mut wal_file_count = 0;
        let mut newest: Option<(u64, OsString)> = None;
        for entry in std::fs::read_dir(&self.links_path).ok()? {
            let name = entry.ok()?.file_name();
            let Some(number) = wal_file_number(&name) else {
                continue;
            };
            wal_file_count += 1;
            if newest.as_ref().is_none_or(|(max, _)| number > *max) {
                newest = Some((number, name));
            }
        }
        let (_, newest_wal_file) = newest?;
        // This follows the link, i.e. it is the size of the primary's file:
        let newest_wal_file_size = std::fs::metadata(self.links_path.join(&newest_wal_file))
            .ok()?
            .len();
        // CURRENT holds the name of the MANIFEST file in use.
        let manifest_file = std::fs::read(self.primary_path.join("CURRENT")).ok()?;
        let manifest_name = OsStr::from_bytes(manifest_file.trim_ascii_end());
        let manifest_file_size = std::fs::metadata(self.primary_path.join(manifest_name))
            .ok()?
            .len();
        Some(PrimaryFingerprint {
            wal_file_count,
            newest_wal_file,
            newest_wal_file_size,
            manifest_file,
            manifest_file_size,
        })
    }
}

/// RocksDB names its WAL files `<number>.log`.
fn is_symlink(path: &Path) -> bool {
    std::fs::symlink_metadata(path)
        .map(|metadata| metadata.file_type().is_symlink())
        .unwrap_or(false)
}

fn is_wal_file_name(name: &OsStr) -> bool {
    wal_file_number(name).is_some()
}

fn wal_file_number(name: &OsStr) -> Option<u64> {
    let digits = name.as_bytes().strip_suffix(b".log")?;
    if digits.is_empty() || !digits.iter().all(u8::is_ascii_digit) {
        return None;
    }
    std::str::from_utf8(digits).ok()?.parse().ok()
}

enum WalChange {
    Created(OsString),
    Deleted(OsString),
}

enum WatchEvents {
    Changes(Vec<WalChange>),
    Unknown,
}

/// A minimal inotify watch on one directory (for created and deleted entries).
struct InotifyWatch {
    fd: libc::c_int,
}

impl InotifyWatch {
    fn new(directory: &Path) -> Option<Self> {
        let path = CString::new(directory.as_os_str().as_bytes()).ok()?;
        // SAFETY: plain system calls; the path is a valid C string for the duration of the call.
        unsafe {
            let fd = libc::inotify_init1(libc::IN_CLOEXEC | libc::IN_NONBLOCK);
            if fd < 0 {
                return None;
            }
            let mask = libc::IN_CREATE | libc::IN_MOVED_TO | libc::IN_DELETE | libc::IN_MOVED_FROM;
            if libc::inotify_add_watch(fd, path.as_ptr(), mask) < 0 {
                libc::close(fd);
                return None;
            }
            Some(Self { fd })
        }
    }

    fn wait_for_events(&self, timeout_ms: i32) -> WatchEvents {
        let mut poll_fd = libc::pollfd {
            fd: self.fd,
            events: libc::POLLIN,
            revents: 0,
        };
        // SAFETY: `poll_fd` is a valid, exclusively borrowed struct.
        let ready = unsafe { libc::poll(&mut poll_fd, 1, timeout_ms) };
        if ready <= 0 {
            return WatchEvents::Changes(vec![]); // timeout (or an interrupted call)
        }
        let mut buffer = [0u8; 16 * 1024];
        // SAFETY: the buffer is valid for writes of its length.
        let length = unsafe {
            libc::read(
                self.fd,
                buffer.as_mut_ptr() as *mut libc::c_void,
                buffer.len(),
            )
        };
        if length <= 0 {
            return WatchEvents::Changes(vec![]);
        }
        let header_size = std::mem::size_of::<libc::inotify_event>();
        let mut changes = Vec::new();
        let mut offset = 0usize;
        while offset + header_size <= length as usize {
            // SAFETY: the kernel wrote a complete event header at this offset; it is read
            // unaligned, since the byte buffer has no alignment guarantee.
            let event: libc::inotify_event = unsafe {
                std::ptr::read_unaligned(buffer.as_ptr().add(offset) as *const libc::inotify_event)
            };
            let name_start = offset + header_size;
            let name_end = name_start + event.len as usize;
            if name_end > length as usize {
                return WatchEvents::Unknown;
            }
            if event.mask & (libc::IN_Q_OVERFLOW | libc::IN_IGNORED) != 0 {
                return WatchEvents::Unknown;
            }
            // The name is padded with NUL bytes:
            let name_bytes: Vec<u8> = buffer[name_start..name_end]
                .iter()
                .copied()
                .take_while(|byte| *byte != 0)
                .collect();
            let name = OsString::from_vec(name_bytes);
            if is_wal_file_name(&name) {
                if event.mask & (libc::IN_CREATE | libc::IN_MOVED_TO) != 0 {
                    changes.push(WalChange::Created(name));
                } else if event.mask & (libc::IN_DELETE | libc::IN_MOVED_FROM) != 0 {
                    changes.push(WalChange::Deleted(name));
                }
            }
            offset = name_end;
        }
        WatchEvents::Changes(changes)
    }
}

impl Drop for InotifyWatch {
    fn drop(&mut self) {
        // SAFETY: the descriptor is owned by this struct and closed exactly once.
        unsafe {
            libc::close(self.fd);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    fn link_names(directory: &WalLinkDirectory) -> Vec<String> {
        let mut names: Vec<String> = std::fs::read_dir(directory.links_path())
            .unwrap()
            .map(|entry| entry.unwrap().file_name().to_string_lossy().to_string())
            .collect();
        names.sort();
        names
    }

    fn wait_until(condition: impl Fn() -> bool) -> bool {
        let start = Instant::now();
        while start.elapsed() < Duration::from_secs(5) {
            if condition() {
                return true;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        false
    }

    #[test]
    fn fingerprint_changes_with_the_wal_and_manifest_of_the_primary() {
        let dir = tempfile::tempdir().unwrap();
        let primary = dir.path().join("primary");
        let links = dir.path().join("links");
        std::fs::create_dir(&primary).unwrap();
        let probe = PrimaryChangeProbe::new(&primary, &links);
        assert_eq!(probe.fingerprint(), None); // no link directory

        std::fs::write(primary.join("CURRENT"), b"MANIFEST-000005\n").unwrap();
        std::fs::write(primary.join("MANIFEST-000005"), b"m").unwrap();
        std::fs::write(primary.join("000009.log"), b"a").unwrap();
        std::fs::write(primary.join("000012.log"), b"a").unwrap();
        let directory = WalLinkDirectory::prepare(&primary, &links).unwrap();
        let start = probe.fingerprint().unwrap();
        assert_eq!(probe.fingerprint().unwrap(), start);

        // An older WAL file does not matter (the primary only appends to the newest):
        std::fs::write(primary.join("000009.log"), b"aaaa").unwrap();
        assert_eq!(probe.fingerprint().unwrap(), start);

        std::fs::write(primary.join("000012.log"), b"ab").unwrap();
        let wal_grown = probe.fingerprint().unwrap();
        assert_ne!(wal_grown, start);

        std::fs::write(primary.join("000013.log"), b"").unwrap();
        assert_eq!(probe.fingerprint().unwrap(), wal_grown); // not linked yet
        directory.sync().unwrap();
        let wal_added = probe.fingerprint().unwrap();
        assert_ne!(wal_added, wal_grown);

        std::fs::write(primary.join("MANIFEST-000005"), b"mm").unwrap();
        let manifest_grown = probe.fingerprint().unwrap();
        assert_ne!(manifest_grown, wal_added);

        std::fs::write(primary.join("MANIFEST-000014"), b"mm").unwrap();
        std::fs::write(primary.join("CURRENT"), b"MANIFEST-000014\n").unwrap();
        assert_ne!(probe.fingerprint().unwrap(), manifest_grown);

        // A link whose file is gone:
        std::fs::remove_file(primary.join("000013.log")).unwrap();
        assert_eq!(probe.fingerprint(), None);
    }

    #[test]
    fn prepare_refuses_a_directory_with_files_and_deletes_none() {
        let dir = tempfile::tempdir().unwrap();
        let primary = dir.path().join("primary");
        std::fs::create_dir(&primary).unwrap();
        std::fs::write(primary.join("000012.log"), b"a").unwrap();
        // The links path points at a directory with real files (e.g. misconfigured):
        let links = dir.path().join("links");
        std::fs::create_dir(&links).unwrap();
        std::fs::write(links.join("000007.log"), b"data").unwrap();
        std::os::unix::fs::symlink(primary.join("000001.log"), links.join("000001.log")).unwrap();

        let error = WalLinkDirectory::prepare(&primary, &links).unwrap_err();
        assert!(error.contains("not a symbolic link"), "{}", error);
        assert_eq!(std::fs::read(links.join("000007.log")).unwrap(), b"data");
        assert!(is_symlink(&links.join("000001.log")));

        // Neither does a sync delete a file which is not a link:
        let directory = WalLinkDirectory {
            primary_path: primary.clone(),
            links_path: links.clone(),
        };
        directory.sync().unwrap();
        assert_eq!(std::fs::read(links.join("000007.log")).unwrap(), b"data");
        assert!(!links.join("000001.log").exists());
        assert!(is_symlink(&links.join("000012.log")));
    }

    #[test]
    fn links_follow_the_wal_files_of_the_primary() {
        let dir = tempfile::tempdir().unwrap();
        let primary = dir.path().join("primary");
        let links = dir.path().join("links");
        std::fs::create_dir(&primary).unwrap();
        std::fs::write(primary.join("000012.log"), b"a").unwrap();
        std::fs::write(primary.join("000013.sst"), b"b").unwrap();
        std::fs::write(primary.join("LOG"), b"c").unwrap();
        std::fs::write(primary.join("x.log"), b"d").unwrap();
        // Leftovers of an earlier run:
        std::fs::create_dir(&links).unwrap();
        std::os::unix::fs::symlink(primary.join("000001.log"), links.join("000001.log")).unwrap();

        let directory = WalLinkDirectory::prepare(&primary, &links).unwrap();
        assert_eq!(link_names(&directory), vec!["000012.log"]);
        assert_eq!(std::fs::read(links.join("000012.log")).unwrap(), b"a");

        let keep_alive = Arc::new(());
        directory.clone().spawn_watcher(Arc::downgrade(&keep_alive));
        std::thread::sleep(Duration::from_millis(200)); // let the watch get established

        std::fs::write(primary.join("000020.log"), b"e").unwrap();
        std::fs::write(primary.join("000021.sst"), b"f").unwrap();
        assert!(wait_until(
            || link_names(&directory) == vec!["000012.log", "000020.log"]
        ));

        std::fs::remove_file(primary.join("000012.log")).unwrap();
        assert!(wait_until(|| link_names(&directory) == vec!["000020.log"]));

        // Nothing but the WAL files themselves was touched in the primary's directory:
        let mut primary_names: Vec<String> = std::fs::read_dir(&primary)
            .unwrap()
            .map(|entry| entry.unwrap().file_name().to_string_lossy().to_string())
            .collect();
        primary_names.sort();
        assert_eq!(
            primary_names,
            vec!["000013.sst", "000020.log", "000021.sst", "LOG", "x.log"]
        );
    }
}
