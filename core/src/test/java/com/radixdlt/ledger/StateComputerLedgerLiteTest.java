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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import com.google.common.collect.ImmutableList;
import com.radixdlt.consensus.*;
import com.radixdlt.consensus.bft.*;
import com.radixdlt.consensus.vertexstore.ExecutedVertex;
import com.radixdlt.consensus.vertexstore.VertexStoreState;
import com.radixdlt.crypto.Blake2b256Hasher;
import com.radixdlt.crypto.HashUtils;
import com.radixdlt.crypto.Hasher;
import com.radixdlt.lang.Option;
import com.radixdlt.ledger.StateComputerLedger.ExecutedTransaction;
import com.radixdlt.ledger.StateComputerLedger.StateComputer;
import com.radixdlt.ledger.StateComputerLedger.StateComputerPrepareResult;
import com.radixdlt.lite.LiteMustHaltException;
import com.radixdlt.lite.LitePendingConsensusCommits;
import com.radixdlt.monitoring.MetricsInitializer;
import com.radixdlt.rev2.REv2ToConsensus;
import com.radixdlt.serialization.DefaultSerialization;
import com.radixdlt.statecomputer.commit.LedgerProof;
import com.radixdlt.statecomputer.commit.LedgerProofOrigin;
import com.radixdlt.transactions.NotarizedTransactionHash;
import com.radixdlt.transactions.RawLedgerTransaction;
import com.radixdlt.utils.TimeSupplier;
import java.util.*;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Lite validator: the ledger executes on top of consensus commits which the database (written by
 * another node) does not show yet, and checks them once it does.
 */
public final class StateComputerLedgerLiteTest {
  private static final long EPOCH = 3;
  private static final long DB_STATE_VERSION = 122;
  private static final long COMMITTED_STATE_VERSION = 123;

  private final Hasher hasher = new Blake2b256Hasher(DefaultSerialization.getInstance());
  private final StateComputer stateComputer = mock(StateComputer.class);
  private final Map<Long, LedgerHashes> database = new HashMap<>();
  private final LedgerHashes dbHashes = randomHashes();
  private final LedgerHashes committedHashes = randomHashes();
  private final LedgerHeader dbHeader =
      LedgerHeader.create(EPOCH, Round.of(4), DB_STATE_VERSION, dbHashes, 1000, 1000);
  private final LedgerHeader committedHeader =
      LedgerHeader.create(EPOCH, Round.of(5), COMMITTED_STATE_VERSION, committedHashes, 1001, 1001);
  private final RawLedgerTransaction committedTransaction =
      RawLedgerTransaction.create(new byte[] {1, 2, 3});

  private LitePendingConsensusCommits pending;
  private StateComputerLedger ledger;
  // The vertex which this node's consensus committed (DB_STATE_VERSION -> COMMITTED_STATE_VERSION)
  private ExecutedVertex committedVertex;
  // A proposal on top of it
  private VertexWithHash proposal;

  private static LedgerHashes randomHashes() {
    return LedgerHashes.create(HashUtils.random256(), HashUtils.random256(), HashUtils.random256());
  }

  @Before
  public void setup() {
    this.pending =
        new LitePendingConsensusCommits(
            stateVersion ->
                Option.option(this.database.get(stateVersion)).map(REv2ToConsensus::ledgerHashes),
            2);
    this.ledger =
        new StateComputerLedger(
            mock(TimeSupplier.class),
            LedgerProofBundle.mockedOfHeader(dbHeader),
            stateComputer,
            hasher,
            new MetricsInitializer().initialize(),
            Optional.of(this.pending));

    // The committed vertex is now the root of the vertex store; a proposal builds on it.
    final var root = Vertex.createInitialEpochVertex(committedHeader).withId(hasher);
    this.committedVertex = mock(ExecutedVertex.class, RETURNS_DEEP_STUBS);
    when(this.committedVertex.vertex().getParentHeader().getLedgerHeader()).thenReturn(dbHeader);
    when(this.committedVertex.vertex().getParentVertexId()).thenReturn(HashUtils.random256());
    when(this.committedVertex.getLedgerHeader()).thenReturn(committedHeader);
    when(this.committedVertex.getVertexHash()).thenReturn(root.hash());
    final ExecutedTransaction executed =
        new ExecutedTransaction() {
          @Override
          public RawLedgerTransaction transaction() {
            return committedTransaction;
          }

          @Override
          public Option<NotarizedTransactionHash> notarizedTransactionHash() {
            return Option.none();
          }
        };
    when(this.committedVertex.successfulTransactions())
        .thenAnswer(i -> java.util.stream.Stream.of(executed));

    final var qcOnRoot = QuorumCertificate.createInitialEpochQC(root, committedHeader);
    this.proposal =
        Vertex.create(qcOnRoot, Round.of(6), List.of(), BFTValidatorId.random(), 1002)
            .withId(hasher);
    when(stateComputer.prepare(any(), anyList(), any(), any(), any()))
        .thenReturn(new StateComputerPrepareResult(ImmutableList.of(), 0, randomHashes()));
  }

  private void consensusCommits(ExecutedVertex vertex) {
    final var vertexStoreState = mock(VertexStoreState.class, RETURNS_DEEP_STUBS);
    when(vertexStoreState.getHighQC().highestCommittedQC().getProcessedCommit(any()))
        .thenReturn(
            Optional.of(
                new ProcessedQcCommit.OfConensusQc(
                    mock(BFTHeader.class),
                    mock(LedgerProof.class),
                    mock(LedgerProofOrigin.Consensus.class))));
    this.ledger
        .bftCommittedUpdateEventProcessor()
        .process(new BFTCommittedUpdate(ImmutableList.of(vertex), vertexStoreState));
  }

  @Test
  public void without_the_pending_commit_the_proposal_cannot_be_prepared() {
    // This is why a lite validator needs the pending commits: the root is not in the database.
    assertThat(this.ledger.prepare(new LinkedList<>(), this.proposal)).isEmpty();
  }

  @Test
  public void a_consensus_commit_writes_nothing_and_the_proposal_executes_on_top_of_it() {
    consensusCommits(this.committedVertex);
    verify(stateComputer, never()).commit(any(), any());
    assertThat(this.pending.pending()).containsExactly(this.committedVertex);

    assertThat(this.ledger.prepare(new LinkedList<>(), this.proposal)).isPresent();
    final ArgumentCaptor<List<ExecutedVertex>> ancestors = ArgumentCaptor.forClass(List.class);
    verify(stateComputer)
        .prepare(eq(dbHashes), ancestors.capture(), eq(committedHashes), any(), any());
    assertThat(ancestors.getValue()).containsExactly(this.committedVertex);
  }

  private static ExecutedVertex laterVertex(long stateVersion) {
    final var vertex = mock(ExecutedVertex.class, RETURNS_DEEP_STUBS);
    when(vertex.successfulTransactions()).thenAnswer(i -> java.util.stream.Stream.empty());
    when(vertex.getVertexHash()).thenReturn(HashUtils.random256());
    when(vertex.getLedgerHeader())
        .thenReturn(
            LedgerHeader.create(EPOCH, Round.of(stateVersion), stateVersion, randomHashes(), 0, 0));
    return vertex;
  }

  @Test
  public void the_epoch_in_which_the_lite_validator_started_is_executed() {
    // Votes in the start epoch are withheld by the pacemaker (LiteStartEpochVoteWithholding),
    // not by the ledger: the vertex is executed on top of the pending commit.
    consensusCommits(this.committedVertex);
    assertThat(this.ledger.prepare(new LinkedList<>(), this.proposal)).isPresent();
    verify(stateComputer).prepare(any(), anyList(), any(), any(), any());
  }

  @Test
  public void a_pending_commit_which_is_also_on_the_path_is_executed_once() {
    // The vertex store was rebuilt (BFT sync) on a root older than the own last commit: the
    // committed vertex is pending and on the path from the root.
    consensusCommits(this.committedVertex);
    final var previous = new LinkedList<ExecutedVertex>();
    previous.add(this.committedVertex);
    assertThat(this.ledger.prepare(previous, this.proposal)).isPresent();
    final ArgumentCaptor<List<ExecutedVertex>> ancestors = ArgumentCaptor.forClass(List.class);
    verify(stateComputer).prepare(any(), ancestors.capture(), any(), any(), any());
    assertThat(ancestors.getValue()).containsExactly(this.committedVertex);
  }

  @Test
  public void pending_commits_which_are_no_ancestors_are_not_executed() {
    // Another committed vertex on top of the committed one (not an ancestor of the proposal):
    final var descendant = laterVertex(124);
    final var committedHash = this.committedVertex.getVertexHash();
    when(descendant.vertex().getParentVertexId()).thenReturn(committedHash);
    consensusCommits(this.committedVertex);
    consensusCommits(descendant);
    assertThat(this.ledger.prepare(new LinkedList<>(), this.proposal)).isPresent();
    final ArgumentCaptor<List<ExecutedVertex>> ancestors = ArgumentCaptor.forClass(List.class);
    verify(stateComputer).prepare(any(), ancestors.capture(), any(), any(), any());
    assertThat(ancestors.getValue()).containsExactly(this.committedVertex);
  }

  @Test
  public void too_many_pending_commits_stop_voting() {
    consensusCommits(this.committedVertex);
    consensusCommits(laterVertex(124));
    consensusCommits(laterVertex(125));
    assertThat(this.ledger.prepare(new LinkedList<>(), this.proposal)).isEmpty();
    verify(stateComputer, never()).prepare(any(), anyList(), any(), any(), any());
  }

  @Test
  public void proposals_exclude_the_transactions_of_pending_commits() {
    consensusCommits(this.committedVertex);
    this.ledger.getTransactionsForProposal(Round.of(6), List.of());
    final ArgumentCaptor<List<ExecutedTransaction>> excluded = ArgumentCaptor.forClass(List.class);
    verify(stateComputer).getTransactionsForProposal(excluded.capture());
    assertThat(excluded.getValue())
        .extracting(ExecutedTransaction::transaction)
        .containsExactly(committedTransaction);
  }

  private void primaryWrites(LedgerHashes hashes) {
    this.database.put(COMMITTED_STATE_VERSION, hashes);
    final var proof =
        new LedgerProof(
            REv2ToConsensus.ledgerHeader(committedHeader),
            new LedgerProofOrigin.Consensus(HashUtils.random256(), List.of()));
    when(stateComputer.commit(any(), isNull()))
        .thenReturn(LedgerProofBundle.mockedOfHeader(committedHeader));
    this.ledger.advanceToExternalLedger(
        () -> COMMITTED_STATE_VERSION,
        from -> new LedgerExtension(List.of(committedTransaction), proof));
  }

  @Test
  public void once_the_database_shows_the_commit_it_is_checked_and_removed() {
    consensusCommits(this.committedVertex);
    primaryWrites(committedHashes);
    assertThat(this.pending.pending()).isEmpty();
  }

  @Test
  public void a_database_which_differs_from_the_own_commit_halts() {
    consensusCommits(this.committedVertex);
    assertThatThrownBy(() -> primaryWrites(randomHashes()))
        .isInstanceOf(LiteMustHaltException.class);
  }

  @Test
  public void a_commit_which_the_database_already_shows_is_checked_right_away() {
    primaryWrites(committedHashes);
    consensusCommits(this.committedVertex);
    assertThat(this.pending.pending()).isEmpty();
  }
}
