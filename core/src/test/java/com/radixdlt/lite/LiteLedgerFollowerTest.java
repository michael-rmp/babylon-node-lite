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

import static com.radixdlt.environment.deterministic.network.MessageSelector.firstSelector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;

import com.radixdlt.consensus.ProposalLimitsConfig;
import com.radixdlt.consensus.bft.SelfValidatorInfo;
import com.radixdlt.crypto.ECKeyPair;
import com.radixdlt.crypto.Hasher;
import com.radixdlt.environment.*;
import com.radixdlt.environment.deterministic.network.MessageMutator;
import com.radixdlt.genesis.GenesisBuilder;
import com.radixdlt.genesis.GenesisConsensusManagerConfig;
import com.radixdlt.genesis.GenesisData;
import com.radixdlt.genesis.RawGenesisDataWithHash;
import com.radixdlt.harness.deterministic.DeterministicTest;
import com.radixdlt.harness.deterministic.PhysicalNodeConfig;
import com.radixdlt.harness.predicates.NodePredicate;
import com.radixdlt.harness.predicates.NodesPredicate;
import com.radixdlt.lang.Option;
import com.radixdlt.ledger.LedgerUpdate;
import com.radixdlt.ledger.StateComputerLedger;
import com.radixdlt.mempool.RustMempool;
import com.radixdlt.mempool.RustMempoolConfig;
import com.radixdlt.modules.FunctionalRadixNodeModule;
import com.radixdlt.modules.FunctionalRadixNodeModule.ConsensusConfig;
import com.radixdlt.modules.FunctionalRadixNodeModule.LedgerConfig;
import com.radixdlt.modules.FunctionalRadixNodeModule.NodeStorageConfig;
import com.radixdlt.modules.FunctionalRadixNodeModule.SafetyRecoveryConfig;
import com.radixdlt.modules.StateComputerConfig;
import com.radixdlt.monitoring.Metrics;
import com.radixdlt.monitoring.MetricsInitializer;
import com.radixdlt.protocol.ProtocolConfig;
import com.radixdlt.protocol.RustProtocolUpdate;
import com.radixdlt.rev2.Decimal;
import com.radixdlt.rev2.NetworkDefinition;
import com.radixdlt.rev2.REv2TransactionsAndProofReader;
import com.radixdlt.serialization.Serialization;
import com.radixdlt.statecomputer.RustStateComputer;
import com.radixdlt.transaction.LedgerSyncLimitsConfig;
import com.radixdlt.transaction.REv2TransactionAndProofStore;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Lite validator: a {@link StateComputerLedger} with a {@link LiteStateComputer} follows the ledger
 * database which a regular validator node writes while running consensus.
 */
public final class LiteLedgerFollowerTest {
  private static final long ROUNDS_PER_EPOCH = 5;

  @Rule public TemporaryFolder primaryFolder = new TemporaryFolder();
  @Rule public TemporaryFolder liteFolder = new TemporaryFolder();

  private final GenesisData genesis =
      GenesisBuilder.createTestGenesisWithNumValidators(
          1,
          Decimal.ONE,
          GenesisConsensusManagerConfig.Builder.testWithRoundsPerEpoch(ROUNDS_PER_EPOCH));

  private DeterministicTest createPrimary() {
    return DeterministicTest.builder()
        .addPhysicalNodes(PhysicalNodeConfig.createBatch(1, true))
        .messageSelector(firstSelector())
        .messageMutator(MessageMutator.dropTimeouts())
        .functionalNodeModule(
            new FunctionalRadixNodeModule(
                NodeStorageConfig.tempFolder(primaryFolder),
                true,
                SafetyRecoveryConfig.MOCKED,
                ConsensusConfig.of(1000),
                LedgerConfig.stateComputerNoSync(StateComputerConfig.rev2().withGenesis(genesis))));
  }

  private File findPrimaryStateManagerDir() throws Exception {
    try (var paths = Files.walk(primaryFolder.getRoot().toPath())) {
      return paths
          .filter(path -> path.getFileName().toString().equals("state_manager"))
          .findFirst()
          .orElseThrow()
          .toFile();
    }
  }

  private static void setWritable(File dir, boolean writable) {
    for (final var file : dir.listFiles()) {
      assertTrue(file.setWritable(writable, false));
    }
    assertTrue(dir.setWritable(writable, false));
  }

  private NodeRustEnvironment createLiteEnvironment(
      File primaryStateManagerDir, ProtocolConfig protocolConfig) {
    final var config =
        new StateManagerConfig(
            NetworkDefinition.INT_TEST_NET,
            Option.some(new RustMempoolConfig(10 * 1024 * 1024, 20)),
            Option.none(),
            new DatabaseBackendConfig(primaryStateManagerDir.getPath()),
            DatabaseConfig.forTesting(),
            LoggingConfig.getDefault(),
            StateTreeGcConfig.forTesting(),
            LedgerProofsGcConfig.forTesting(),
            LedgerSyncLimitsConfig.defaults(),
            protocolConfig,
            false,
            ScenariosExecutionConfig.NONE);
    // The start checks demand a database which this process cannot write to. In this test, both
    // nodes are the same process and user, so the permissions are taken away just for the start.
    // (The running primary keeps its open files, and creates no new ones in this short moment.)
    setWritable(primaryStateManagerDir, false);
    try {
      return NodeRustEnvironment.createLite(
          RawGenesisDataWithHash.fromGenesisData(genesis),
          tx -> {},
          () -> {},
          config,
          liteFolder.getRoot().getPath(),
          100,
          16,
          true,
          true);
    } finally {
      setWritable(primaryStateManagerDir, true);
    }
  }

  @Test
  public void lite_ledger_follows_the_database_of_a_running_validator() throws Exception {
    try (var primary = createPrimary()) {
      primary.startNode(0);
      // Start behind the protocol updates of the test network (enacted in the first epochs):
      primary.runUntilState(NodesPredicate.nodeAt(0, NodePredicate.atOrOverEpoch(6)));
      final var primaryStore = primary.getInstance(0, REv2TransactionAndProofStore.class);

      final Metrics metrics = new MetricsInitializer().initialize();
      try (var liteEnvironment =
          createLiteEnvironment(
              findPrimaryStateManagerDir(), primary.getInstance(0, ProtocolConfig.class))) {
        // Arrange: a ledger with the lite state computer, as the lite node will wire it up.
        final var liteStore = new REv2TransactionAndProofStore(metrics, liteEnvironment);
        final var initialProof =
            new REv2TransactionsAndProofReader(
                    liteStore, LedgerSyncLimitsConfig.defaults(), metrics)
                .getLatestProofBundle()
                .orElseThrow();
        final List<LedgerUpdate> ledgerUpdates = new ArrayList<>();
        final var hasher = primary.getInstance(0, Hasher.class);
        final var stateComputer =
            new LiteStateComputer(
                new RustStateComputer(metrics, liteEnvironment),
                new RustMempool(metrics, liteEnvironment),
                new RustProtocolUpdate(metrics, liteEnvironment),
                ProposalLimitsConfig.testDefaults(),
                hasher,
                ledgerUpdates::add,
                success -> {},
                primary.getInstance(0, Serialization.class),
                round -> null,
                metrics,
                new SelfValidatorInfo(ECKeyPair.generateNew().getPublicKey(), Optional.empty()),
                initialProof);
        final var ledger =
            new StateComputerLedger(
                System::currentTimeMillis, initialProof, stateComputer, hasher, metrics);
        final var follower =
            new LiteLedgerFollower(
                ledger,
                new RustLiteFollower(metrics, liteEnvironment),
                liteStore,
                LedgerSyncLimitsConfig.defaults(),
                3,
                0,
                // A catch-up may be postponed for a long time: the test only passes if the
                // writes of the validator are noticed without it.
                600_000,
                () -> false);
        final var startStateVersion = initialProof.resultantStateVersion();
        assertThat(startStateVersion)
            .isEqualTo(primaryStore.getLatestProof().orElseThrow().stateVersion());

        // Nothing new yet:
        assertThat(follower.followOnce()).isZero();
        assertThat(ledgerUpdates).isEmpty();

        // Act: the validator progresses over two epoch changes; the lite ledger follows.
        primary.runUntilState(NodesPredicate.nodeAt(0, NodePredicate.atOrOverEpoch(8)));
        final var primaryProof = primaryStore.getLatestProof().orElseThrow();
        final var advancedBy = follower.followOnce();

        // Assert: the lite ledger is exactly where the validator is...
        assertThat(advancedBy).isEqualTo((int) (primaryProof.stateVersion() - startStateVersion));
        assertThat(ledgerUpdates).isNotEmpty();
        final var lastUpdate = ledgerUpdates.get(ledgerUpdates.size() - 1);
        assertThat(lastUpdate.committedProofBundle().primaryProof()).isEqualTo(primaryProof);
        // ... every transaction was passed on exactly once...
        assertThat(
                ledgerUpdates.stream()
                    .mapToInt(update -> update.committedNonProtocolUpdateTransactions().size())
                    .sum())
            .isEqualTo(advancedBy);
        // ... and both epoch changes were announced:
        assertThat(ledgerUpdates.stream().filter(update -> update.epochChange().isPresent()))
            .hasSize(2);

        // Following again without progress changes nothing:
        final var updateCount = ledgerUpdates.size();
        assertThat(follower.followOnce()).isZero();
        assertThat(ledgerUpdates).hasSize(updateCount);

        // And it keeps following:
        primary.runUntilState(NodesPredicate.nodeAt(0, NodePredicate.atOrOverEpoch(9)));
        assertThat(follower.followOnce()).isPositive();
        assertThat(
                ledgerUpdates.get(ledgerUpdates.size() - 1).committedProofBundle().primaryProof())
            .isEqualTo(primaryStore.getLatestProof().orElseThrow());
      }
    }
  }
}
