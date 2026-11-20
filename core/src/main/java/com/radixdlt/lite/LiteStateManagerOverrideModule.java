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

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.radixdlt.consensus.BFTConfiguration;
import com.radixdlt.consensus.ProposalLimitsConfig;
import com.radixdlt.consensus.bft.SelfValidatorInfo;
import com.radixdlt.consensus.vertexstore.PersistentVertexStore;
import com.radixdlt.consensus.vertexstore.VertexStoreState;
import com.radixdlt.crypto.Hasher;
import com.radixdlt.environment.*;
import com.radixdlt.genesis.GenesisProvider;
import com.radixdlt.ledger.LedgerProofBundle;
import com.radixdlt.ledger.LedgerUpdate;
import com.radixdlt.mempool.MempoolAddSuccess;
import com.radixdlt.mempool.MempoolRelayDispatcher;
import com.radixdlt.mempool.RustMempool;
import com.radixdlt.monitoring.Metrics;
import com.radixdlt.protocol.RustProtocolUpdate;
import com.radixdlt.rev2.REv2StateComputer;
import com.radixdlt.rev2.REv2ToConsensus;
import com.radixdlt.serialization.DeserializeException;
import com.radixdlt.serialization.DsonOutput;
import com.radixdlt.serialization.Serialization;
import com.radixdlt.statecomputer.RustStateComputer;
import com.radixdlt.store.NodeStorageLocation;
import com.radixdlt.transactions.RawNotarizedTransaction;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Lite validator: the bindings which differ from a regular node's {@code REv2StateManagerModule}
 * and {@code REv2LedgerRecoveryModule}. It is applied as an override of those.
 *
 * <ul>
 *   <li>The Rust environment only reads the primary's database; {@code db.location} is this node's
 *       own directory.
 *   <li>The state computer never writes ({@link LiteStateComputer}).
 *   <li>The vertex store is persisted in, and recovered from, this node's own directory - and never
 *       read from the primary's database, where the primary keeps its own.
 * </ul>
 */
public final class LiteStateManagerOverrideModule extends AbstractModule {
  private static final Logger log = LogManager.getLogger();

  private final LiteConfig liteConfig;
  private final GenesisProvider genesisProvider;
  private final ProposalLimitsConfig proposalLimitsConfig;

  public LiteStateManagerOverrideModule(
      LiteConfig liteConfig,
      GenesisProvider genesisProvider,
      ProposalLimitsConfig proposalLimitsConfig) {
    this.liteConfig = liteConfig;
    this.genesisProvider = genesisProvider;
    this.proposalLimitsConfig = proposalLimitsConfig;
  }

  @Provides
  @Singleton
  private NodeRustEnvironment liteNodeRustEnvironment(
      MempoolRelayDispatcher<RawNotarizedTransaction> mempoolRelayDispatcher,
      FatalPanicHandler fatalPanicHandler,
      StateManagerConfig regularConfig,
      @NodeStorageLocation String ownPath) {
    final var config =
        new StateManagerConfig(
            regularConfig.networkDefinition(),
            regularConfig.mempoolConfigOpt(),
            regularConfig.vertexLimitsConfigOpt(),
            new DatabaseBackendConfig(this.liteConfig.primaryStateManagerPath()),
            regularConfig.databaseConfig(),
            regularConfig.loggingConfig(),
            regularConfig.stateTreeGcConfig(),
            regularConfig.ledgerProofsGcConfig(),
            regularConfig.ledgerSyncLimitsConfig(),
            regularConfig.protocolConfig(),
            regularConfig.noFees(),
            regularConfig.scenariosExecutionConfig());
    log.info(
        "Starting as a LITE validator: reading the ledger database {} of another node; own"
            + " directory is {}",
        this.liteConfig.primaryStateManagerPath(),
        ownPath);
    return NodeRustEnvironment.createLite(
        this.genesisProvider,
        mempoolRelayDispatcher,
        fatalPanicHandler,
        config,
        ownPath,
        this.liteConfig.maxOpenFiles(),
        this.liteConfig.blockCacheMb(),
        this.liteConfig.cacheIndexAndFilterBlocks(),
        this.liteConfig.walLinks());
  }

  @Provides
  @Singleton
  private REv2StateComputer liteStateComputer(
      RustStateComputer stateComputer,
      RustMempool mempool,
      RustProtocolUpdate rustProtocolUpdate,
      EventDispatcher<LedgerUpdate> ledgerUpdateEventDispatcher,
      Hasher hasher,
      EventDispatcher<MempoolAddSuccess> mempoolAddSuccessEventDispatcher,
      Serialization serialization,
      BFTConfiguration initialBftConfiguration,
      Metrics metrics,
      SelfValidatorInfo selfValidatorInfo,
      LedgerProofBundle initialLatestProof,
      LiteIdentityGuard identityGuard) {
    // Every epoch change is checked against this process' identity before anyone sees it.
    final EventDispatcher<LedgerUpdate> guardedLedgerUpdateDispatcher =
        ledgerUpdate -> {
          if (ledgerUpdate.epochChange().isPresent()) {
            final var validatorSet =
                ledgerUpdate.epochChange().unwrap().bftConfiguration().getValidatorSet();
            identityGuard
                .violation(validatorSet)
                .ifPresent(
                    reason -> {
                      throw new LiteMustHaltException("Epoch change: " + reason);
                    });
            identityGuard
                .reportParticipation(validatorSet)
                .ifPresent(
                    reason ->
                        org.apache.logging.log4j.LogManager.getLogger(LiteIdentityGuard.class)
                            .info("Lite validator at epoch change: {}", reason));
          }
          ledgerUpdateEventDispatcher.dispatch(ledgerUpdate);
        };
    return new LiteStateComputer(
        stateComputer,
        mempool,
        rustProtocolUpdate,
        this.proposalLimitsConfig,
        hasher,
        guardedLedgerUpdateDispatcher,
        mempoolAddSuccessEventDispatcher,
        serialization,
        initialBftConfiguration.getProposerElection(),
        metrics,
        selfValidatorInfo,
        initialLatestProof);
  }

  @Provides
  @Singleton
  private LiteVertexStoreFile liteVertexStoreFile(@NodeStorageLocation String ownPath) {
    return new LiteVertexStoreFile(Path.of(ownPath, "lite_consensus"));
  }

  @Provides
  private PersistentVertexStore vertexStore(
      LiteVertexStoreFile file, Metrics metrics, Serialization serialization) {
    return state -> {
      metrics.misc().vertexStoreSaved().inc();
      file.save(serialization.toDson(state.toSerialized(), DsonOutput.Output.ALL));
    };
  }

  @Provides
  @Singleton
  private VertexStoreState vertexStoreState(
      LedgerProofBundle latestProof,
      LiteVertexStoreFile file,
      Serialization serialization,
      Hasher hasher) {
    final var currentEpoch = latestProof.resultantEpoch();
    final var vertexStoreState =
        file.load()
            .map(
                bytes -> {
                  try {
                    return serialization.fromDson(
                        bytes, VertexStoreState.SerializedVertexStoreState.class);
                  } catch (DeserializeException e) {
                    throw new RuntimeException("Unable to recover VertexStore", e);
                  }
                })
            .filter(state -> state.isForEpoch(currentEpoch))
            .map(state -> state.toVertexStoreState(hasher))
            .orElseGet(
                () ->
                    VertexStoreState.createNewForNextEpoch(
                        REv2ToConsensus.ledgerHeader(latestProof.epochInitialHeader()),
                        latestProof.resultantEpoch(),
                        hasher));
    log.info("Initial Vertex Store State (lite): {}", vertexStoreState);
    return vertexStoreState;
  }
}
