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

import com.google.common.collect.ImmutableList;
import com.radixdlt.consensus.vertexstore.ExecutedVertex;
import com.radixdlt.lang.Option;
import com.radixdlt.ledger.PendingConsensusCommits;
import com.radixdlt.rev2.REv2ToConsensus;
import com.radixdlt.statecomputer.commit.LedgerHashes;
import java.util.List;
import java.util.Objects;
import java.util.function.LongFunction;
import java.util.stream.Collectors;

/**
 * Lite validator: see {@link PendingConsensusCommits}. A difference between the database and the
 * own consensus is either a safety violation in the network or a bug of the lite validator; either
 * way, the process halts for good (see {@link LiteMustHaltException}).
 */
public final class LitePendingConsensusCommits implements PendingConsensusCommits {
  private final LongFunction<Option<LedgerHashes>> databaseHashesAt;
  private final int maxPending;
  // Replaced (never modified) under the ledger lock; read without it for proposals.
  private volatile ImmutableList<ExecutedVertex> pending = ImmutableList.of();

  /**
   * @param databaseHashesAt the ledger hashes which the database shows right after the given state
   *     version (empty if it does not reach it)
   * @param maxPending above this number of pending vertices, this node executes nothing more (and
   *     so does not vote) until the database catches up
   */
  public LitePendingConsensusCommits(
      LongFunction<Option<LedgerHashes>> databaseHashesAt, int maxPending) {
    this.databaseHashesAt = Objects.requireNonNull(databaseHashesAt);
    this.maxPending = maxPending;
  }

  @Override
  public void add(List<ExecutedVertex> committed) {
    // Each vertex may appear only once: the ledger executes the list as one chain.
    final var known =
        this.pending.stream().map(ExecutedVertex::getVertexHash).collect(Collectors.toSet());
    final var builder = ImmutableList.<ExecutedVertex>builder().addAll(this.pending);
    for (var vertex : committed) {
      if (known.add(vertex.getVertexHash())) {
        builder.add(vertex);
      }
    }
    this.pending = builder.build();
  }

  @Override
  public List<ExecutedVertex> pending() {
    return this.pending;
  }

  @Override
  public boolean blocksPreparing() {
    return this.pending.size() > this.maxPending;
  }

  /**
   * Whether this node's consensus has committed the end of an epoch which the database does not
   * show yet. The next epoch only starts with the database (see {@code EpochManager}), so the
   * follower should then catch up as soon as possible.
   */
  public boolean awaitsEpochChange() {
    return this.pending.stream().anyMatch(vertex -> vertex.getLedgerHeader().isEndOfEpoch());
  }

  @Override
  public void verifyAndRemoveUpTo(long ledgerStateVersion) {
    final var current = this.pending;
    int reached = 0;
    for (var vertex : current) {
      final var header = vertex.getLedgerHeader();
      final var stateVersion = header.getStateVersion();
      if (stateVersion > ledgerStateVersion) {
        break;
      }
      final var databaseHashes = this.databaseHashesAt.apply(stateVersion);
      if (databaseHashes.isEmpty()) {
        // The view reaches the state version, so it must show it: a broken database view.
        throw new LiteMustStopException(
            "The database shows nothing at state version %s, although its top is %s"
                .formatted(stateVersion, ledgerStateVersion));
      }
      final var databaseConsensusHashes = REv2ToConsensus.ledgerHashes(databaseHashes.unwrap());
      if (!databaseConsensusHashes.equals(header.getHashes())) {
        throw new LiteMustHaltException(
            ("The ledger database differs from what this node's consensus committed: at state"
                    + " version %s (epoch %s, round %s), the consensus committed %s, the database"
                    + " shows %s")
                .formatted(
                    stateVersion,
                    header.getEpoch(),
                    header.getRound(),
                    header.getHashes(),
                    databaseConsensusHashes));
      }
      reached++;
    }
    if (reached > 0) {
      this.pending = current.subList(reached, current.size());
    }
  }
}
