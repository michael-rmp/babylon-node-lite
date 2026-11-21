# babylon-node-lite

**Run additional Radix validators on the server of your existing node, without a second copy of
the ledger.**

A second Radix validator normally means a second full node: another ~630 GB ledger (and growing),
another disk, another sync, usually another server. A **lite validator** shares the ledger of the
node you already run. It needs ~3.5 GB of RAM and a few GB of disk.

```
 one server
┌──────────────────────────────────────────────────────────────┐
│  your node (official release, unchanged)                     │
│  ├─ validator A                                              │
│  └─ ledger database (~630 GB) ◄──── read-only ──┐            │
│                                                 │            │
│  lite validator  ───────────────────────────────┘            │
│  └─ validator B: own key, own address, own stake             │
└──────────────────────────────────────────────────────────────┘
```

## How it works

- Your node, itself an active validator, keeps doing all the work of a node: it executes,
  commits and stores the ledger. Because it takes part in consensus, every block is in its database
  as soon as it is committed.
- The lite validator opens your node's database **read-only** and follows it every few hundred
  milliseconds. It has its own key and its own validator address, and takes part in consensus
  like any other validator: it proposes and votes.
- It writes nothing into the ledger. A block that its consensus has committed counts only once
  your node's database shows the same block.

## Who it is for

Operators who already run a Radix validator node on Linux (systemd) and want to run one or more
further validators, without paying for a further node each time. The node whose ledger is shared
must itself be an active validator; a plain full node as the source is not supported (see
[limits](docs/lite-validator/README.md#limits)).

## Is it safe for my validator?

That is the first design goal. The lite validator runs as its own Linux user and sees only your
node's state database, mounted read-only: the operating system itself refuses any write. On top of
that it refuses to start on any configuration error, never uses your validator's key, and gets a
hard memory limit, so that a lack of memory stops the lite validator, not your validator. Your node
runs unchanged on the official release.
Details: [safety of the validator's database](docs/lite-validator/README.md#safety-of-the-validators-database).

## Status

**Experimental.** Based on Babylon node `v1.4.0.0`. Tested in local networks and running on
mainnet as a validator next to a standard validator since November 2026. Known limits: it is away
for a few minutes at a protocol update, does not vote in the epoch in which it was started, and
may occasionally miss a proposal. See [limits](docs/lite-validator/README.md#limits).

## Getting started

1. Read the [overview](docs/lite-validator/README.md).
2. Follow **[all installation steps at a glance](docs/lite-validator/INSTALL.md#all-steps-at-a-glance)**:
   every command in order, to copy and paste: build the package, create the user, create the key
   with `keygen`, write `environment` and `mainnet.config`, create the systemd service and the
   disk watchdog, start, register. Each step is explained in detail further down in
   [INSTALL.md](docs/lite-validator/INSTALL.md). Either download the package from
   [Releases](https://github.com/michael-rmp/babylon-node-lite/releases) or build it yourself on a
   build machine with 16 GB RAM; then about an hour on the validator host.
3. Run it as a follower first, then register it as a validator.

| Documentation | |
|---|---|
| [Overview](docs/lite-validator/README.md) | what it is, how it works, safety, limits |
| [Installation](docs/lite-validator/INSTALL.md) | all steps at a glance; requirements, build, install, first start, update |
| [Configuration](docs/lite-validator/CONFIGURATION.md) | all settings, ports, exit codes |
| [Operations](docs/lite-validator/OPERATIONS.md) | monitoring, memory, maintenance, troubleshooting |
| [Testing](docs/lite-validator/TESTING.md) | what was tested and how |
| [Development](docs/lite-validator/DEVELOPMENT.md) | code overview, building, tests |

## About this repository

- A derivative of the [Radix Babylon node](https://github.com/radixdlt/babylon-node), release
  `v1.4.0.0`; everything the lite validator adds or changes is in the commits on top of that tag
  (`git diff v1.4.0.0`, list of changed upstream files in [NOTICE](NOTICE)). The upstream README is
  in [docs/UPSTREAM-README.md](docs/UPSTREAM-README.md).
- Not affiliated with, endorsed by or supported by RDX Works or the Radix Foundation.
- License: [Radix License, Version 1.0](LICENSE), as the upstream project. Provided as is, without
  warranty; you are responsible for running it.
- Questions and bugs: GitHub issues. Security issues: [SECURITY.md](SECURITY.md).
- Developed with the assistance of Claude (Anthropic).
