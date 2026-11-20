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

package com.radixdlt.lite;

import com.radixdlt.ledger.LedgerExtension;
import com.radixdlt.ledger.StateComputerLedger;
import com.radixdlt.transaction.GetSyncableTxnsAndProofError;
import com.radixdlt.transaction.LedgerSyncLimitsConfig;
import com.radixdlt.transaction.REv2TransactionAndProofStore;
import com.radixdlt.transactions.RawLedgerTransaction;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Lite validator: moves the ledger forward to whatever the primary node has written to its
 * database. This replaces both the commit from consensus and the ledger sync of a regular node.
 */
public final class LiteLedgerFollower {
  private final StateComputerLedger ledger;
  private final RustLiteFollower rustLiteFollower;
  private final REv2TransactionAndProofStore transactionStore;
  private final LedgerSyncLimitsConfig limitsConfig;
  private final int gateRetries;
  private final int minIntervalMs;
  private final int maxPostponementMs;
  private final BooleanSupplier urgent;

  public LiteLedgerFollower(
      StateComputerLedger ledger,
      RustLiteFollower rustLiteFollower,
      REv2TransactionAndProofStore transactionStore,
      LedgerSyncLimitsConfig limitsConfig,
      int gateRetries,
      int minIntervalMs,
      int maxPostponementMs,
      BooleanSupplier urgent) {
    this.ledger = Objects.requireNonNull(ledger);
    this.rustLiteFollower = Objects.requireNonNull(rustLiteFollower);
    this.transactionStore = Objects.requireNonNull(transactionStore);
    this.limitsConfig = Objects.requireNonNull(limitsConfig);
    this.gateRetries = gateRetries;
    this.minIntervalMs = minIntervalMs;
    this.maxPostponementMs = maxPostponementMs;
    this.urgent = Objects.requireNonNull(urgent);
  }

  /**
   * Catches up with the primary's database and applies everything new to the ledger (which
   * dispatches the ledger updates). While the primary's files look unchanged, the catch-up itself
   * is postponed (see {@link RustLiteFollower#catchUp}), which makes this cheap to call often.
   *
   * @return the number of transactions by which the ledger advanced
   * @throws LiteRetryLaterException if this attempt failed, but a later one may succeed
   * @throws LiteMustStopException if this process must stop
   */
  public int followOnce() {
    return this.ledger.advanceToExternalLedger(this::catchUp, this::readExtension);
  }

  private long catchUp() {
    // While the own consensus waits for an epoch change from the database, catch up on every
    // tick (no minimum interval, no postponement).
    final var urgent = this.urgent.getAsBoolean();
    return this.rustLiteFollower
        .catchUp(
            this.gateRetries, urgent ? 0 : this.minIntervalMs, urgent ? 0 : this.maxPostponementMs)
        .fold(
            topStateVersion -> topStateVersion.toLong(),
            error -> {
              throw toException(error);
            });
  }

  private static RuntimeException toException(LiteCatchUpError error) {
    return switch (error) {
      case LiteCatchUpError.CatchUpFailed failed -> new LiteRetryLaterException(
          "Catching up with the primary's database failed: " + failed.message());
        // The database view has moved, but could not be verified. It must not be used by
        // anything, and nothing short of stopping guarantees that. A commit of the primary is one
        // atomic write, so this is not expected to ever happen.
      case LiteCatchUpError.InconsistentView inconsistent -> new LiteMustStopException(
          "The primary's database shows an inconsistent state: " + inconsistent.message());
      case LiteCatchUpError.Diverged diverged -> new LiteMustStopException(
          "The primary's database diverged from what was seen before: " + diverged.message());
    };
  }

  private LedgerExtension readExtension(long latestStateVersion) {
    return this.transactionStore
        .getSyncableTxnsAndProof(latestStateVersion + 1, this.limitsConfig)
        .fold(
            txnsAndProof ->
                new LedgerExtension(
                    txnsAndProof.transactions().stream().map(RawLedgerTransaction::create).toList(),
                    txnsAndProof.proof()),
            error -> {
              throw toException(error, latestStateVersion);
            });
  }

  private RuntimeException toException(
      GetSyncableTxnsAndProofError error, long latestStateVersion) {
    return switch (error) {
      case GetSyncableTxnsAndProofError.RefusedToServeProtocolUpdate
      refused -> new LiteMustStopException(
          "The primary node has executed a protocol update at state version %s. A lite"
                  .formatted(refused.refusedProof().stateVersion())
              + " validator does not follow through protocol updates; restart it.");
      case GetSyncableTxnsAndProofError.NothingToServeAtTheGivenStateVersion
      nothing -> new LiteMustStopException(
          "The primary's database has no transactions after state version %s, although it was"
                  .formatted(latestStateVersion)
              + " seen at a higher one.");
      case GetSyncableTxnsAndProofError.FailedToPrepareAResponseWithinLimits
      failed -> new LiteMustStopException(
          "Cannot read the transactions after state version %s within the limits %s."
              .formatted(latestStateVersion, this.limitsConfig));
    };
  }
}
