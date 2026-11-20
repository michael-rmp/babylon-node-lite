# Lite validator

A lite validator is a second (third, …) Radix validator on the **same host** as an existing
Babylon node, **without a second copy of the ledger**. It reads the state database of the
existing node read-only and takes part in consensus with a key and a validator address of its own.

> **Status: experimental.** Built on Babylon node `v1.4.0.0`. Tested in local networks and in
> operation on mainnet next to a standard validator since November 2026 (one host). Not tested on
> Stokenet. Use at your own risk; see the disclaimer in the [Radix License](../../LICENSE).
> This project is not affiliated with, endorsed by or supported by RDX Works or the Radix
> Foundation.

## Why

A mainnet ledger is ~630 GB and grows. Running a second validator normally means a second node
with a second ledger, a second disk and a second sync. A lite validator needs a few GB of RAM and
a few GB of disk next to the existing node instead.

The trade-off is deliberate: a lite validator is meant to be a cheap second validator. It may
occasionally miss a proposal (see [Limits](#limits)); it must never endanger the existing node.

## How it works

```
┌────────────────────────────────┐
│ Standard node (unchanged,      │  the only writer of the ledger
│ official release)              │
│  <db.location>/state_manager ◄─┼─────────┐ read-only bind mount,
└────────────────────────────────┘         │ RocksDB secondary instance
┌────────────────────────────────┐         │
│ Lite validator                 │─────────┘
│  own directory: key, safety    │
│  store, vertex store, address  │
│  book, RocksDB secondary files │
└────────────────────────────────┘
```

- The standard node executes and commits every transaction. The lite validator opens the
  standard node's `state_manager` database as a **RocksDB secondary instance** and catches up with
  it every few hundred milliseconds.
- **Commits come from the disk.** When the lite validator's own consensus commits a vertex, it
  writes nothing: it waits until the standard node's database shows the same vertex, compares the
  hashes and then advances its ledger. A mismatch stops the lite validator for good (exit code 78).
- Ledger sync is replaced by catching up with the database. The lite validator offers no ledger
  sync to other nodes.
- Everything else is the unchanged node code: consensus, P2P, mempool, the Radix Engine for
  executing proposals. Signing and voting are not touched.

## Safety of the validator's database

The highest priority of the design is that the database of the existing validator cannot be
damaged. Four independent layers; the first two do not depend on code of this project:

| Layer | Effect |
|---|---|
| Operating system | The lite process runs as its own user, and its systemd unit shows it only the `state_manager` directory, as a read-only bind mount. The kernel refuses every write, also in case of a bug. Below the top directory of the database path (e.g. `/srv`) nothing else is visible: not the validator's safety store, not its address book. |
| RocksDB secondary | A secondary instance takes no `LOCK`, refuses writes and keeps its own files in its own directory. |
| Lite code | No code path opens the database as a primary; the start is refused if the database is writable for the process; writes to a secondary instance abort; no migrations, no garbage collection jobs. Configuration errors are refused before anything is opened. |
| Resources | Memory limit and high OOM score: if memory runs short, the lite validator dies, not the validator. A watchdog stops all lite validators when the disk runs low. |

What this does **not** cover: the lite validator shares disk, RAM and CPU with the validator. A
full disk or a lack of memory can stop the validator (an outage, not a damage). That is what the
memory limit and the watchdog are for.

Rules that follow from it:

- Never use RocksDB's read-only mode (`OpenForReadOnly`) on the live database, never create
  checkpoints or hard links on the validator's file system. Only the secondary mode is used.
- Never build on the validator host.
- **One key, one process.** A lite validator has a key of its own. Never run two processes with
  the same key, not even as a "backup" on another host: two processes with one key can sign two
  different votes for the same round.

## Limits

- **Protocol updates:** the lite validator does not execute protocol updates itself. When the
  standard node executes one, the lite validator stops and is restarted by systemd after 120 s; it
  is away for ~2–6 minutes and misses its proposals in that time.
- **Same version:** the lite validator and the standard node must be built from the same release.
  Update both together. There is no automatic check of this yet.
- **Start epoch:** after a start, the lite validator does not vote in the current epoch (it
  executes and proposes). This prevents a double vote after a restart. It votes again from the
  next epoch on.
- **The standard node must be an active validator:** as a validator it has every committed block
  in its database at once. A plain full node gets new blocks only through ledger sync, later; a
  lite validator reading it would follow late and miss more proposals. Not supported, not tested.
- **The standard node is needed:** if it stops, the lite validator keeps voting until 50
  consensus commits wait for the database (`lite.max_pending_committed`), then it stops voting
  and continues as soon as the standard node is back.
- **Latency:** a catch-up costs ~80–100 ms on mainnet (RocksDB builds a new view over all SST
  files). Proposals of the lite validator can therefore be slightly late; an occasional missed
  proposal is accepted.
- Linux with systemd only.

## Documents

| Document | Content |
|---|---|
| [INSTALL.md](INSTALL.md) | [All steps at a glance](INSTALL.md#all-steps-at-a-glance) (copy and paste); requirements, building the package, installing, first start, update, removal |
| [CONFIGURATION.md](CONFIGURATION.md) | All settings, ports, exit codes |
| [OPERATIONS.md](OPERATIONS.md) | Monitoring, memory, maintenance, key changes, protocol updates, troubleshooting |
| [TESTING.md](TESTING.md) | What was tested and how: local networks, misconfigurations, memory |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Code overview, building, tests, a local lite validator |
| [ops/](ops) | Package script, `install.sh`, configuration template, watchdog |
