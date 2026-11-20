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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.radixdlt.consensus.LedgerHashes;
import com.radixdlt.consensus.LedgerHeader;
import com.radixdlt.consensus.NextEpoch;
import com.radixdlt.consensus.bft.BFTValidator;
import com.radixdlt.consensus.bft.BFTValidatorId;
import com.radixdlt.consensus.bft.Round;
import com.radixdlt.consensus.vertexstore.ExecutedVertex;
import com.radixdlt.crypto.HashUtils;
import com.radixdlt.lang.Option;
import com.radixdlt.rev2.REv2ToConsensus;
import com.radixdlt.utils.UInt192;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class LitePendingConsensusCommitsTest {
  // What the database shows (consensus-side hashes, converted on read):
  private final Map<Long, LedgerHashes> database = new HashMap<>();
  private final LitePendingConsensusCommits pending =
      new LitePendingConsensusCommits(
          stateVersion ->
              Option.option(this.database.get(stateVersion)).map(REv2ToConsensus::ledgerHashes),
          3);

  private static LedgerHashes randomHashes() {
    return LedgerHashes.create(HashUtils.random256(), HashUtils.random256(), HashUtils.random256());
  }

  private static ExecutedVertex vertex(long stateVersion, LedgerHashes hashes) {
    final var vertex = mock(ExecutedVertex.class);
    when(vertex.getVertexHash()).thenReturn(HashUtils.random256());
    when(vertex.getLedgerHeader())
        .thenReturn(LedgerHeader.create(7, Round.of(stateVersion), stateVersion, hashes, 0, 0));
    return vertex;
  }

  @Test
  public void vertices_wait_until_the_database_reaches_them_and_then_match() {
    final var h11 = randomHashes();
    final var h12 = randomHashes();
    final var v11 = vertex(11, h11);
    final var v12 = vertex(12, h12);
    this.pending.add(List.of(v11, v12));

    this.pending.verifyAndRemoveUpTo(10);
    assertThat(this.pending.pending()).containsExactly(v11, v12);

    // The database jumps over both at once (one commit of the primary):
    this.database.put(11L, h11);
    this.database.put(12L, h12);
    this.pending.verifyAndRemoveUpTo(15);
    assertThat(this.pending.pending()).isEmpty();
  }

  @Test
  public void a_vertex_without_new_state_version_matches_like_its_parent() {
    // After an epoch-ending vertex, the following vertices execute nothing (same header).
    final var h11 = randomHashes();
    final var v11 = vertex(11, h11);
    final var v11again = vertex(11, h11);
    this.pending.add(List.of(v11, v11again));
    this.database.put(11L, h11);
    this.pending.verifyAndRemoveUpTo(11);
    assertThat(this.pending.pending()).isEmpty();
  }

  @Test
  public void a_different_database_halts_for_good() {
    final var v11 = vertex(11, randomHashes());
    this.pending.add(List.of(v11));
    this.database.put(11L, randomHashes());
    assertThatThrownBy(() -> this.pending.verifyAndRemoveUpTo(11))
        .isInstanceOf(LiteMustHaltException.class)
        .hasMessageContaining("state version 11");
    assertThat(this.pending.pending()).containsExactly(v11);
  }

  @Test
  public void a_database_view_without_the_state_version_stops() {
    this.pending.add(List.of(vertex(11, randomHashes())));
    assertThatThrownBy(() -> this.pending.verifyAndRemoveUpTo(11))
        .isInstanceOf(LiteMustStopException.class);
  }

  @Test
  public void too_many_pending_vertices_block_voting() {
    this.pending.add(List.of(vertex(11, randomHashes()), vertex(12, randomHashes())));
    this.pending.add(List.of(vertex(13, randomHashes())));
    assertThat(this.pending.blocksPreparing()).isFalse();
    this.pending.add(List.of(vertex(14, randomHashes())));
    assertThat(this.pending.blocksPreparing()).isTrue();
  }

  @Test
  public void a_pending_end_of_epoch_makes_the_epoch_change_awaited() {
    final var hashes = randomHashes();
    final var endOfEpoch = mock(ExecutedVertex.class);
    when(endOfEpoch.getVertexHash()).thenReturn(HashUtils.random256());
    when(endOfEpoch.getLedgerHeader())
        .thenReturn(
            LedgerHeader.create(
                7,
                Round.of(11),
                11,
                hashes,
                0,
                0,
                NextEpoch.create(
                    8, ImmutableSet.of(BFTValidator.from(BFTValidatorId.random(), UInt192.ONE))),
                null));
    this.pending.add(List.of(vertex(10, randomHashes())));
    assertThat(this.pending.awaitsEpochChange()).isFalse();
    this.pending.add(List.of(endOfEpoch));
    assertThat(this.pending.awaitsEpochChange()).isTrue();

    this.database.put(10L, this.pending.pending().get(0).getLedgerHeader().getHashes());
    this.database.put(11L, hashes);
    this.pending.verifyAndRemoveUpTo(11);
    assertThat(this.pending.awaitsEpochChange()).isFalse();
  }

  @Test
  public void a_vertex_committed_twice_is_pending_once() {
    final var v11 = vertex(11, randomHashes());
    this.pending.add(List.of(v11));
    this.pending.add(List.of(v11));
    assertThat(this.pending.pending()).containsExactly(v11);
  }
}
