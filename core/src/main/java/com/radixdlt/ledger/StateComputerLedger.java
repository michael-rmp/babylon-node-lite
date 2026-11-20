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

package com.radixdlt.ledger;

// Modified in 2026 by michael-rmp for the lite validator (babylon-node-lite), see NOTICE.

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Streams;
import com.google.common.hash.HashCode;
import com.google.inject.Inject;
import com.radixdlt.consensus.*;
import com.radixdlt.consensus.bft.*;
import com.radixdlt.consensus.liveness.ProposalGenerator;
import com.radixdlt.consensus.vertexstore.ExecutedVertex;
import com.radixdlt.consensus.vertexstore.VertexStoreState;
import com.radixdlt.crypto.Hasher;
import com.radixdlt.environment.EventProcessor;
import com.radixdlt.environment.RemoteEventProcessor;
import com.radixdlt.lang.Option;
import com.radixdlt.mempool.MempoolAdd;
import com.radixdlt.monitoring.Metrics;
import com.radixdlt.p2p.NodeId;
import com.radixdlt.rev2.REv2ToConsensus;
import com.radixdlt.transactions.NotarizedTransactionHash;
import com.radixdlt.transactions.RawLedgerTransaction;
import com.radixdlt.transactions.RawNotarizedTransaction;
import com.radixdlt.utils.TimeSupplier;
import java.util.*;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Synchronizes execution */
public final class StateComputerLedger implements Ledger, ProposalGenerator {

  private static final Logger log = LogManager.getLogger();

  public interface ExecutedTransaction {
    RawLedgerTransaction transaction();

    Option<NotarizedTransactionHash> notarizedTransactionHash();
  }

  public static class StateComputerPrepareResult {
    private final List<ExecutedTransaction> executedTransactions;
    private final int rejectedTransactionCount;
    private final NextEpoch nextEpoch;
    private final String nextProtocolVersion;
    private final LedgerHashes ledgerHashes;

    public StateComputerPrepareResult(
        List<ExecutedTransaction> executedTransactions,
        int rejectedTransactionCount,
        NextEpoch nextEpoch,
        String nextProtocolVersion,
        LedgerHashes ledgerHashes) {
      this.executedTransactions = Objects.requireNonNull(executedTransactions);
      this.rejectedTransactionCount = rejectedTransactionCount;
      this.nextEpoch = nextEpoch;
      this.nextProtocolVersion = nextProtocolVersion;
      this.ledgerHashes = ledgerHashes;
    }

    public StateComputerPrepareResult(
        List<ExecutedTransaction> executedTransactions,
        int rejectedTransactionCount,
        LedgerHashes ledgerHashes) {
      this(executedTransactions, rejectedTransactionCount, null, null, ledgerHashes);
    }

    public Option<NextEpoch> getNextEpoch() {
      return Option.option(nextEpoch);
    }

    public Option<String> getNextProtocolVersion() {
      return Option.option(nextProtocolVersion);
    }

    public LedgerHashes getLedgerHashes() {
      return ledgerHashes;
    }

    public List<ExecutedTransaction> getSuccessfullyExecutedTransactions() {
      return executedTransactions;
    }

    public int getRejectedTransactionCount() {
      return rejectedTransactionCount;
    }
  }

  public interface StateComputer {
    void addToMempool(MempoolAdd mempoolAdd, NodeId origin);

    List<RawNotarizedTransaction> getTransactionsForProposal(
        List<ExecutedTransaction> executedTransactions);

    StateComputerPrepareResult prepare(
        LedgerHashes committedLedgerHashes,
        List<ExecutedVertex> preparedUncommittedVertices,
        LedgerHashes preparedUncommittedLedgerHashes,
        List<RawNotarizedTransaction> proposedTransactions,
        RoundDetails roundDetails);

    LedgerProofBundle commit(LedgerExtension ledgerExtension, VertexStoreState vertexStore);
  }

  private final StateComputer stateComputer;
  private final Hasher hasher;
  private final Metrics metrics;
  private final TimeSupplier timeSupplier;
  private final Object commitAndAdvanceLedgerLock;
  private LedgerProofBundle latestProof;
  // Lite validator only (empty for a regular node): consensus commits which the ledger database,
  // written by another node, does not show yet. Set once, right after construction.
  private Optional<PendingConsensusCommits> pendingConsensusCommits;

  @Inject
  public StateComputerLedger(
      TimeSupplier timeSupplier,
      LedgerProofBundle initialLatestProof,
      StateComputer stateComputer,
      Hasher hasher,
      Metrics metrics) {
    this(timeSupplier, initialLatestProof, stateComputer, hasher, metrics, Optional.empty());
  }

  /**
   * Lite validator: injected only where {@link PendingConsensusCommits} is bound (by the lite
   * validator's modules); a regular node, and every setup without that binding, never gets it.
   */
  @Inject(optional = true)
  void setPendingConsensusCommits(PendingConsensusCommits pendingConsensusCommits) {
    this.pendingConsensusCommits = Optional.of(pendingConsensusCommits);
  }

  /** Whether this ledger holds back consensus commits (only in a lite validator). */
  public boolean holdsBackConsensusCommits() {
    return this.pendingConsensusCommits.isPresent();
  }

  public StateComputerLedger(
      TimeSupplier timeSupplier,
      LedgerProofBundle initialLatestProof,
      StateComputer stateComputer,
      Hasher hasher,
      Metrics metrics,
      Optional<PendingConsensusCommits> pendingConsensusCommits) {
    this.pendingConsensusCommits = Objects.requireNonNull(pendingConsensusCommits);
    this.timeSupplier = Objects.requireNonNull(timeSupplier);
    this.stateComputer = Objects.requireNonNull(stateComputer);
    this.hasher = Objects.requireNonNull(hasher);
    this.metrics = Objects.requireNonNull(metrics);
    this.latestProof = initialLatestProof;
    this.commitAndAdvanceLedgerLock = new Object();
  }

  public RemoteEventProcessor<NodeId, MempoolAdd> mempoolAddRemoteEventProcessor() {
    return (node, mempoolAdd) -> stateComputer.addToMempool(mempoolAdd, node);
  }

  public EventProcessor<MempoolAdd> mempoolAddEventProcessor() {
    return mempoolAdd -> stateComputer.addToMempool(mempoolAdd, null);
  }

  @Override
  public List<RawNotarizedTransaction> getTransactionsForProposal(
      Round round, List<ExecutedVertex> prepared) {
    // A lite validator's pending consensus commits are not in the database yet, so their
    // transactions are still in the mempool: exclude them as well.
    final var pending =
        this.pendingConsensusCommits.map(PendingConsensusCommits::pending).orElse(List.of());
    final ImmutableList<ExecutedTransaction> executedTransactions =
        Streams.concat(pending.stream(), prepared.stream())
            .flatMap(ExecutedVertex::successfulTransactions)
            .collect(ImmutableList.toImmutableList());
    return stateComputer.getTransactionsForProposal(executedTransactions);
  }

  @Override
  public Optional<ExecutedVertex> prepare(
      LinkedList<ExecutedVertex> previous, VertexWithHash vertexWithHash) {
    return metrics.ledger().prepare().measure(() -> this.prepareInternal(previous, vertexWithHash));
  }

  /**
   * Lite validator: the ancestors of the given vertex among the pending consensus commits and the
   * given path from the vertex store's root, oldest first. The two may overlap, and the pending
   * vertices may even be descendants of the root: the vertex store can be rebuilt (BFT sync) on a
   * root older than the last own commit. So the ancestors are found by following the parent links
   * back from the vertex's parent; each appears exactly once, in chain order. The walk stops where
   * a link is missing (at the root, or at what the database already shows); the regular checks in
   * {@link #prepareInternal} then decide whether the rest can be executed.
   */
  private static List<ExecutedVertex> liteAncestorChain(
      List<ExecutedVertex> pending, List<ExecutedVertex> previous, Vertex vertex) {
    final var byHash = new HashMap<HashCode, ExecutedVertex>();
    for (var candidate : Iterables.concat(pending, previous)) {
      byHash.putIfAbsent(candidate.getVertexHash(), candidate);
    }
    final var ancestors = new ArrayList<ExecutedVertex>();
    var cursor = vertex.getParentVertexId();
    while (true) {
      final var ancestor = byHash.remove(cursor);
      if (ancestor == null) {
        break;
      }
      ancestors.add(ancestor);
      cursor = ancestor.vertex().getParentVertexId();
    }
    Collections.reverse(ancestors);
    return ancestors;
  }

  private Optional<ExecutedVertex> prepareInternal(
      LinkedList<ExecutedVertex> previous, VertexWithHash vertexWithHash) {
    final var vertex = vertexWithHash.vertex();
    final LedgerHeader parentHeader = vertex.getParentHeader().getLedgerHeader();

    final StateComputerPrepareResult result;
    synchronized (this.commitAndAdvanceLedgerLock) {
      // A lite validator executes on top of its consensus commits which the database does not
      // show yet (they come before the given previous vertices, which start after the root):
      final List<ExecutedVertex> previousVertices;
      if (this.pendingConsensusCommits.isPresent()) {
        final var pending = this.pendingConsensusCommits.get();
        if (pending.blocksPreparing()) {
          this.metrics.ledger().litePreparesWithheld().inc();
          return Optional.empty();
        }
        previousVertices = liteAncestorChain(pending.pending(), previous, vertex);
      } else {
        previousVertices = previous;
      }

      final var latestStateVersion = this.latestProof.resultantStateVersion();
      final var latestLedgerHashes =
          REv2ToConsensus.ledgerHashes(this.latestProof.primaryProof().ledgerHeader().hashes());

      if (latestStateVersion > parentHeader.getStateVersion()) {
        // We have received a stale vertex to prepare - ignore it.
        return Optional.empty();
      }

      if (parentHeader.isEndOfEpoch() || parentHeader.isProtocolUpdate()) {
        // Don't execute any transactions and commit to the same LedgerHeader if in the process of
        // an epoch change or a protocol update.
        // Updates to LedgerHeader here may cause a disagreement on the next epoch
        // initial vertex if a TC occurs for example.
        return Optional.of(
            new ExecutedVertex(
                vertexWithHash, parentHeader, ImmutableList.of(), this.timeSupplier.currentTime()));
      }

      // It's possible that this function is called with a list of vertices which starts with some
      // committed vertices. By matching on the state version, we remove the already committed
      // vertices from the "previous" list.
      final var previousVertexIterator = Iterators.peekingIterator(previousVertices.iterator());
      while (previousVertexIterator.hasNext()) {
        final var previousVertexBaseHeader =
            previousVertexIterator.peek().vertex().getParentHeader().getLedgerHeader();
        if (previousVertexBaseHeader.getStateVersion() == latestStateVersion) {
          if (!previousVertexBaseHeader.getHashes().equals(latestLedgerHashes)) {
            // Some vertex has matched on the state version (which isn't particularly improbable,
            // since only a number of transactions must coincide). However, the ledger hashes did
            // not match, which means that other vertices than ours were committed.
            return Optional.empty();
          }
          break;
        }
        previousVertexIterator.next();
      }
      final var verticesInExtension = Streams.stream(previousVertexIterator).toList();

      if (verticesInExtension.isEmpty()) {
        // None of the previous vertices has matched our current top of ledger. There is still a
        // possibility that the proposed vertex is built right on that top. But if not, then we
        // cannot progress.
        if (!parentHeader.getHashes().equals(latestLedgerHashes)) {
          return Optional.empty();
        }
      }

      result =
          this.stateComputer.prepare(
              latestLedgerHashes,
              verticesInExtension,
              parentHeader.getHashes(),
              vertex.getTransactions(),
              RoundDetails.fromVertex(vertexWithHash));
    }

    final LedgerHeader ledgerHeader =
        LedgerHeader.create(
            parentHeader.getEpoch(),
            vertex.getRound(),
            parentHeader.getStateVersion() + result.getSuccessfullyExecutedTransactions().size(),
            result.getLedgerHashes(),
            vertex.getQCToParent().getWeightedTimestampOfSignatures(),
            vertex.proposerTimestamp(),
            result.getNextEpoch().or((NextEpoch) null),
            result.getNextProtocolVersion().or((String) null));

    return Optional.of(
        new ExecutedVertex(
            vertexWithHash,
            ledgerHeader,
            result.getSuccessfullyExecutedTransactions(),
            this.timeSupplier.currentTime()));
  }

  public EventProcessor<BFTCommittedUpdate> bftCommittedUpdateEventProcessor() {
    return committedUpdate -> {
      updateCommittedVerticesMetrics(committedUpdate);

      final ImmutableList<RawLedgerTransaction> transactions =
          committedUpdate.committed().stream()
              .flatMap(ExecutedVertex::successfulTransactions)
              .map(ExecutedTransaction::transaction)
              .collect(ImmutableList.toImmutableList());

      final var maybeProcessedQcCommit =
          committedUpdate
              .vertexStoreState()
              .getHighQC()
              .highestCommittedQC()
              .getProcessedCommit(hasher);

      maybeProcessedQcCommit.ifPresentOrElse(
          processedQcCommit -> {
            switch (processedQcCommit) {
              case ProcessedQcCommit.OfConensusQc ofConensusQc -> {
                if (this.pendingConsensusCommits.isPresent()) {
                  // A lite validator writes nothing: the commit waits until the database
                  // shows it.
                  synchronized (this.commitAndAdvanceLedgerLock) {
                    final var pending = this.pendingConsensusCommits.get();
                    pending.add(committedUpdate.committed());
                    pending.verifyAndRemoveUpTo(this.latestProof.resultantStateVersion());
                    this.metrics
                        .ledger()
                        .liteConsensusCommitsPending()
                        .set(pending.pending().size());
                  }
                } else {
                  final var ledgerExtension =
                      new LedgerExtension(transactions, ofConensusQc.ledgerProof());
                  metrics
                      .ledger()
                      .commit()
                      .measure(
                          () -> this.commit(ledgerExtension, committedUpdate.vertexStoreState()));
                }
              }
              case ProcessedQcCommit.OfInitialEpochQc ofInitialEpochQc -> {
                // no-op, ignore
                this.metrics.ledger().ignoredBftCommittedUpdates().inc();
              }
            }
          },
          () -> {
            // no-op, ignore
            this.metrics.ledger().ignoredBftCommittedUpdates().inc();
          });
    };
  }

  private void updateCommittedVerticesMetrics(BFTCommittedUpdate committedUpdate) {
    final var numCommittedFallbackVertices =
        committedUpdate.committed().stream().filter(v -> v.vertex().isFallback()).count();
    final var numCommittedNonFallbackVertices =
        committedUpdate.committed().size() - numCommittedFallbackVertices;

    metrics
        .bft()
        .committedVertices()
        .label(new Metrics.Bft.CommittedVertex(true))
        .inc(numCommittedFallbackVertices);

    metrics
        .bft()
        .committedVertices()
        .label(new Metrics.Bft.CommittedVertex(false))
        .inc(numCommittedNonFallbackVertices);
  }

  /**
   * Lite validator: advances this ledger to the state of a ledger database written by another node.
   *
   * <p>Everything happens under the same lock as {@link #prepare} and the regular commit, so a
   * {@code prepare} sees either the state before or after, never a moving one:
   *
   * <ol>
   *   <li>{@code catchUp} moves the database view forward and returns its top state version;
   *   <li>while this ledger is behind it, {@code readExtension} is asked for an extension starting
   *       right after the given state version, which is then handed to the {@link StateComputer}
   *       like a synced one.
   * </ol>
   *
   * <p>Exceptions thrown by the given functions propagate; this ledger then remains at the last
   * extension which was applied.
   *
   * @return the number of transactions by which this ledger advanced
   */
  public int advanceToExternalLedger(
      LongSupplier catchUp, LongFunction<LedgerExtension> readExtension) {
    int transactionCount = 0;
    synchronized (this.commitAndAdvanceLedgerLock) {
      final var topStateVersion = catchUp.getAsLong();
      while (this.latestProof.resultantStateVersion() < topStateVersion) {
        final var latestStateVersion = this.latestProof.resultantStateVersion();
        final var extension = readExtension.apply(latestStateVersion);
        if (extension.proof().stateVersion() <= latestStateVersion) {
          throw new IllegalStateException(
              "External ledger extension %s does not progress the current ledger %s"
                  .formatted(extension.proof(), this.latestProof));
        }
        final var extensionToApply = extension.getExtensionFrom(latestStateVersion);
        this.latestProof = this.stateComputer.commit(extensionToApply, null);
        transactionCount += extensionToApply.transactions().size();
      }
      final var latestStateVersion = this.latestProof.resultantStateVersion();
      this.pendingConsensusCommits.ifPresent(
          pending -> {
            pending.verifyAndRemoveUpTo(latestStateVersion);
            this.metrics.ledger().liteConsensusCommitsPending().set(pending.pending().size());
          });
    }
    this.metrics.ledger().stateVersion().set(this.latestProof.resultantStateVersion());
    this.metrics.ledger().syncTransactionsProcessed().inc(transactionCount);
    return transactionCount;
  }

  public EventProcessor<LedgerExtension> syncEventProcessor() {
    return p -> metrics.ledger().commit().measure(() -> this.commit(p, null));
  }

  /**
   * Appends a useful suffix of the given {@link LedgerExtension} to the persistent ledger (while
   * also writing the new {@link VertexStoreState}, if non-{@literal null}).
   *
   * <p>A "useful suffix" is a sub-list of transactions that are not yet persisted in the local
   * ledger. The implementation will resolve this sub-list based on the end state version from the
   * {@link LedgerExtension#proof() proof}. If the useful suffix is empty, then this method will
   * no-op and return successfully (with just a trace log, since this kind of situation is most
   * likely a harmless "stale response").
   *
   * <p>This method <b>silently assumes</b> (i.e. does not check) the following facts:
   *
   * <ul>
   *   <li>The proof is signed by a quorum of validators from the current validator set.
   *   <li>The validators' signatures are valid.
   * </ul>
   *
   * <p>This method <b>verifies</b> the following preconditions:
   *
   * <ul>
   *   <li>The useful suffix does not start with the immediate next state version of the local
   *       ledger's end - in case of such "gap", an {@link IllegalArgumentException} is thrown.
   *   <li>Any of the transactions within useful suffix is non-parseable - in this case, a {@link
   *       InvalidCommitRequestException} is propagated from the Rust state computer.
   *   <li>A transaction root hash computed while applying the useful suffix differs from the one
   *       specified by ny of the {@link LedgerExtension#proof() proof} - in this case, a {@link
   *       InvalidCommitRequestException} is propagated from the Rust state computer.
   * </ul>
   */
  private void commit(LedgerExtension ledgerExtension, @Nullable VertexStoreState vertexStore) {
    final var proofToCommit = ledgerExtension.proof();

    final int extensionTransactionCount; // for metrics purposes only
    synchronized (this.commitAndAdvanceLedgerLock) {
      final var latestStateVersion = this.latestProof.resultantStateVersion();

      if (proofToCommit.stateVersion() <= latestStateVersion) {
        log.trace(
            "Ignoring the ledger extension {} which would not progress the current ledger {}",
            proofToCommit,
            latestProof);
        return;
      }

      final var extensionToCommit = ledgerExtension.getExtensionFrom(latestStateVersion);

      // persist
      this.latestProof = this.stateComputer.commit(extensionToCommit, vertexStore);

      extensionTransactionCount = extensionToCommit.transactions().size();
    }

    // TODO: move all of the following to post-persist event handling (while considering the
    // synchronization theoretically needed here).

    this.metrics.ledger().stateVersion().set(this.latestProof.resultantStateVersion());
    if (vertexStore == null) {
      this.metrics.ledger().syncTransactionsProcessed().inc(extensionTransactionCount);
    } else {
      this.metrics.ledger().bftTransactionsProcessed().inc(extensionTransactionCount);
    }
  }
}
