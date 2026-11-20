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
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.OptionalBinder;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.radixdlt.addressing.Addressing;
import com.radixdlt.consensus.BFTConfiguration;
import com.radixdlt.consensus.bft.Self;
import com.radixdlt.consensus.bft.SelfValidatorInfo;
import com.radixdlt.consensus.liveness.VoteWithholding;
import com.radixdlt.crypto.ECDSASecp256k1PublicKey;
import com.radixdlt.environment.*;
import com.radixdlt.ledger.LedgerProofBundle;
import com.radixdlt.ledger.PendingConsensusCommits;
import com.radixdlt.ledger.StateComputerLedger;
import com.radixdlt.monitoring.Metrics;
import com.radixdlt.rev2.ComponentAddress;
import com.radixdlt.sync.messages.local.*;
import com.radixdlt.transaction.LedgerSyncLimitsConfig;
import com.radixdlt.transaction.REv2TransactionAndProofStore;
import com.radixdlt.utils.AsynchronousSystem;
import java.util.Optional;

/**
 * Lite validator: replaces the ledger sync of a regular node ({@code SyncServiceModule} and {@code
 * EpochsSyncModule}). The ledger advances only by following the primary's database; nothing is
 * requested from, or served to, other nodes.
 */
public final class LiteFollowerModule extends AbstractModule {
  private final LiteConfig liteConfig;

  public LiteFollowerModule(LiteConfig liteConfig) {
    this.liteConfig = liteConfig;
  }

  @Override
  public void configure() {
    bind(LiteConfig.class).toInstance(this.liteConfig);

    // Other components dispatch these events of the regular ledger sync:
    var eventBinder =
        Multibinder.newSetBinder(binder(), new TypeLiteral<Class<?>>() {}, LocalEvents.class)
            .permitDuplicates();
    eventBinder.addBinding().toInstance(SyncCheckTrigger.class);
    eventBinder.addBinding().toInstance(SyncCheckReceiveStatusTimeout.class);
    eventBinder.addBinding().toInstance(SyncRequestTimeout.class);
    eventBinder.addBinding().toInstance(LocalSyncRequest.class);
    eventBinder.addBinding().toInstance(SyncLedgerUpdateTimeout.class);

    bind(LiteIdentityStartCheck.class).asEagerSingleton();

    // Makes StateComputerLedger hold back consensus commits (see PendingConsensusCommits):
    bind(PendingConsensusCommits.class).to(LitePendingConsensusCommits.class);
    // No vote in the epoch in which this process started (it still executes and proposes):
    OptionalBinder.newOptionalBinder(binder(), VoteWithholding.class)
        .setBinding()
        .to(LiteStartEpochVoteWithholding.class);
  }

  @Provides
  @Singleton
  private LiteStartEpochVoteWithholding liteStartEpochVoteWithholding(
      LedgerProofBundle initialLatestProof, Metrics metrics) {
    return new LiteStartEpochVoteWithholding(
        initialLatestProof.resultantEpoch(), metrics.ledger().liteVotesWithheld());
  }

  @Provides
  @Singleton
  private LitePendingConsensusCommits litePendingConsensusCommits(
      RustLiteFollower rustLiteFollower) {
    return new LitePendingConsensusCommits(
        rustLiteFollower::ledgerHashesAt, this.liteConfig.maxPendingCommitted());
  }

  @Provides
  @Singleton
  private LiteIdentityGuard liteIdentityGuard(
      @Self ECDSASecp256k1PublicKey selfKey,
      SelfValidatorInfo selfValidatorInfo,
      Addressing addressing) {
    final Optional<ComponentAddress> primaryAddress =
        this.liteConfig.primaryValidatorAddress().isEmpty()
            ? Optional.empty()
            : Optional.of(
                addressing.decodeValidatorAddress(this.liteConfig.primaryValidatorAddress()));
    return new LiteIdentityGuard(
        selfKey,
        selfValidatorInfo.bftValidatorId().map(id -> id.getValidatorAddress()),
        primaryAddress,
        addressing::encode);
  }

  /**
   * Checks the identity against the validator set of the current epoch while the node is being set
   * up, i.e. before it connects to anyone. A violation fails the setup with a {@link
   * LiteMustHaltException}; the application ends with {@link LiteMustHaltException#EXIT_CODE}.
   */
  static final class LiteIdentityStartCheck {
    @Inject
    LiteIdentityStartCheck(LiteIdentityGuard guard, BFTConfiguration initialBftConfiguration) {
      guard
          .violation(initialBftConfiguration.getValidatorSet())
          .ifPresent(
              reason -> {
                throw new LiteMustHaltException("At start: " + reason);
              });
      guard
          .reportParticipation(initialBftConfiguration.getValidatorSet())
          .ifPresent(
              reason ->
                  org.apache.logging.log4j.LogManager.getLogger(LiteIdentityGuard.class)
                      .info("Lite validator at start: {}", reason));
    }
  }

  @Provides
  @Singleton
  private RustLiteFollower rustLiteFollower(
      Metrics metrics, NodeRustEnvironment nodeRustEnvironment) {
    return new RustLiteFollower(metrics, nodeRustEnvironment);
  }

  @Provides
  @Singleton
  private LiteLedgerFollower liteLedgerFollower(
      StateComputerLedger ledger,
      RustLiteFollower rustLiteFollower,
      REv2TransactionAndProofStore transactionStore,
      LedgerSyncLimitsConfig limitsConfig,
      LitePendingConsensusCommits pendingConsensusCommits) {
    return new LiteLedgerFollower(
        ledger,
        rustLiteFollower,
        transactionStore,
        limitsConfig,
        this.liteConfig.gateRetries(),
        (int) this.liteConfig.minFollowIntervalMs(),
        (int) this.liteConfig.followIntervalMs(),
        pendingConsensusCommits::awaitsEpochChange);
  }

  @Provides
  @Singleton
  private LiteFollowerRunner liteFollowerRunner(
      LiteLedgerFollower follower, FatalPanicHandler fatalPanicHandler) {
    return new LiteFollowerRunner(
        follower,
        fatalPanicHandler,
        () -> AsynchronousSystem.exit(LiteMustHaltException.EXIT_CODE),
        this.liteConfig.maxConsecutiveFailures(),
        this.liteConfig.followIntervalMs());
  }

  // The periodic tick re-uses the (otherwise unused) trigger event of the regular ledger sync.
  @Provides
  @Singleton
  private EventProducer<SyncCheckTrigger> followTickProducer(
      ScheduledEventDispatcher<SyncCheckTrigger> dispatcher) {
    return new EventProducer<>(
        SyncCheckTrigger::create, dispatcher, this.liteConfig.checkIntervalMs());
  }

  @ProvidesIntoSet
  private StartProcessorOnRunner followTickStart(EventProducer<SyncCheckTrigger> producer) {
    return new StartProcessorOnRunner(Runners.SYNC, producer::start);
  }

  @ProvidesIntoSet
  private EventProcessorOnRunner<?> followTickReschedule(EventProducer<SyncCheckTrigger> producer) {
    return new EventProcessorOnRunner<>(Runners.SYNC, SyncCheckTrigger.class, producer);
  }

  @ProvidesIntoSet
  private EventProcessorOnRunner<?> followOnTick(LiteFollowerRunner runner) {
    return new EventProcessorOnRunner<>(
        Runners.SYNC, SyncCheckTrigger.class, trigger -> runner.tick());
  }

  @Provides
  @Singleton
  private LiteMemoryTrimmer liteMemoryTrimmer(RustLiteFollower rustLiteFollower) {
    return new LiteMemoryTrimmer(
        rustLiteFollower::trimMemory, this.liteConfig.mallocTrimIntervalS());
  }

  // On the same runner as the follower, but outside the ledger lock.
  @ProvidesIntoSet
  private EventProcessorOnRunner<?> trimMemoryOnTick(LiteMemoryTrimmer trimmer) {
    return new EventProcessorOnRunner<>(
        Runners.SYNC, SyncCheckTrigger.class, trigger -> trimmer.tick());
  }

  /** Consensus asks for the ledger to be synced to some proof: look at the database right away. */
  @ProvidesIntoSet
  private EventProcessorOnRunner<?> followOnLocalSyncRequest(LiteFollowerRunner runner) {
    return new EventProcessorOnRunner<>(
        Runners.SYNC, LocalSyncRequest.class, request -> runner.tick());
  }
}
