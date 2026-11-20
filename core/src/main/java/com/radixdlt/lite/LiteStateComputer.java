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
import com.radixdlt.consensus.ProposalLimitsConfig;
import com.radixdlt.consensus.bft.SelfValidatorInfo;
import com.radixdlt.consensus.liveness.ProposerElection;
import com.radixdlt.consensus.vertexstore.VertexStoreState;
import com.radixdlt.crypto.Hasher;
import com.radixdlt.environment.EventDispatcher;
import com.radixdlt.ledger.LedgerExtension;
import com.radixdlt.ledger.LedgerProofBundle;
import com.radixdlt.ledger.LedgerUpdate;
import com.radixdlt.mempool.MempoolAddSuccess;
import com.radixdlt.mempool.RustMempool;
import com.radixdlt.monitoring.Metrics;
import com.radixdlt.protocol.RustProtocolUpdate;
import com.radixdlt.rev2.REv2StateComputer;
import com.radixdlt.serialization.Serialization;
import com.radixdlt.statecomputer.RustStateComputer;
import com.radixdlt.statecomputer.commit.CommitSummary;
import com.radixdlt.statecomputer.commit.LedgerProof;
import com.radixdlt.utils.UInt32;

/**
 * The state computer of a lite validator. It prepares vertices like a regular node, but it never
 * writes to the ledger database, which belongs to another node (the "primary"):
 *
 * <ul>
 *   <li>an extension which the {@link LiteLedgerFollower} has read from the primary's database is
 *       only turned into a {@link LedgerUpdate} (epoch changes, mempool, consensus sync);
 *   <li>a commit coming from this node's own consensus is refused for now (a lite validator does
 *       not take part in consensus before phase 5 of the development plan);
 *   <li>protocol updates are executed by the primary; this process stops when it sees one.
 * </ul>
 */
public final class LiteStateComputer extends REv2StateComputer {

  public LiteStateComputer(
      RustStateComputer stateComputer,
      RustMempool mempool,
      RustProtocolUpdate rustProtocolUpdate,
      ProposalLimitsConfig proposalLimitsConfig,
      Hasher hasher,
      EventDispatcher<LedgerUpdate> ledgerUpdateEventDispatcher,
      EventDispatcher<MempoolAddSuccess> mempoolAddSuccessEventDispatcher,
      Serialization serialization,
      ProposerElection initialProposerElection,
      Metrics metrics,
      SelfValidatorInfo selfValidatorInfo,
      LedgerProofBundle initialLatestProof) {
    super(
        stateComputer,
        mempool,
        rustProtocolUpdate,
        proposalLimitsConfig,
        hasher,
        ledgerUpdateEventDispatcher,
        mempoolAddSuccessEventDispatcher,
        serialization,
        initialProposerElection,
        metrics,
        selfValidatorInfo,
        initialLatestProof);
  }

  @Override
  protected CommitSummary persist(LedgerExtension ledgerExtension, VertexStoreState vertexStore) {
    if (vertexStore != null) {
      throw new IllegalStateException(
          "A lite validator does not commit from its own consensus: the ledger database is"
              + " written by the primary node only");
    }
    // Nothing to write: the extension was read from the database. The summary is only logged.
    return new CommitSummary(ImmutableList.of(), UInt32.fromNonNegativeInt(0));
  }

  @Override
  protected LedgerProof applyKnownPendingProtocolUpdate() {
    throw new LiteMustStopException(
        "The primary node is executing a protocol update. A lite validator does not execute"
            + " protocol updates; restart it once the primary has finished.");
  }
}
