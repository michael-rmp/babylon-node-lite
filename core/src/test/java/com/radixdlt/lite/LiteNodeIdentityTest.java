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
import static org.junit.Assert.assertTrue;

import com.google.common.base.Throwables;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.radixdlt.RadixNodeModule;
import com.radixdlt.consensus.bft.SelfValidatorInfo;
import com.radixdlt.consensus.liveness.VoteWithholding;
import com.radixdlt.crypto.ECKeyPair;
import com.radixdlt.crypto.RadixKeyStore;
import com.radixdlt.environment.NodeRustEnvironment;
import com.radixdlt.environment.RemoteEventDispatcher;
import com.radixdlt.genesis.GenesisBuilder;
import com.radixdlt.genesis.GenesisConsensusManagerConfig;
import com.radixdlt.genesis.RawGenesisDataWithHash;
import com.radixdlt.ledger.StateComputerLedger;
import com.radixdlt.networks.Network;
import com.radixdlt.p2p.NodeId;
import com.radixdlt.rev2.Decimal;
import com.radixdlt.sync.messages.remote.LedgerStatusUpdate;
import com.radixdlt.utils.properties.RuntimeProperties;
import java.io.File;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A lite validator must never act with the identity of another validator: when it runs with the
 * very key of a validator (and is told to take its validator address from the genesis), it refuses
 * to start. (A regular node with the same configuration is that validator - that is the control.)
 */
public final class LiteNodeIdentityTest {
  private static final Network NETWORK = Network.INTEGRATIONTESTNET;

  @Rule public TemporaryFolder folder = new TemporaryFolder();

  private Injector createNode(
      File keyStoreFile, RawGenesisDataWithHash genesis, Map<String, String> overrides)
      throws Exception {
    final var properties = RuntimeProperties.empty();
    properties.set("network.id", "" + NETWORK.getId());
    properties.set("host.ip", "127.0.0.1");
    properties.set("node.key.path", keyStoreFile.getAbsolutePath());
    properties.set("consensus.use_genesis_for_validator_address", "true");
    overrides.forEach(properties::set);
    return Guice.createInjector(new RadixNodeModule(properties, NETWORK, genesis));
  }

  private static void setWritable(File dir, boolean writable) {
    for (final var file : dir.listFiles()) {
      assertTrue(file.setWritable(writable, false));
    }
    assertTrue(dir.setWritable(writable, false));
  }

  @Test
  public void lite_node_with_the_key_of_a_genesis_validator_refuses_to_start() throws Exception {
    final var validatorKey = ECKeyPair.generateNew();
    final var keyStoreFile = new File(folder.newFolder(), "keystore.ks");
    RadixKeyStore.fromFile(keyStoreFile, "".toCharArray(), true).writeKeyPair("node", validatorKey);
    final var genesis =
        RawGenesisDataWithHash.fromGenesisData(
            GenesisBuilder.createTestGenesisWithSingleValidator(
                validatorKey.getPublicKey(),
                Decimal.ONE,
                GenesisConsensusManagerConfig.Builder.testDefaults()));
    final var primaryDir = folder.newFolder("primary");
    final var liteDir = folder.newFolder("lite");

    // The control: a regular node with this key is the genesis validator. (Creating it also
    // creates the database which the lite node then reads.)
    final var regularNode =
        createNode(keyStoreFile, genesis, Map.of("db.location", primaryDir.getAbsolutePath()));
    final var regularRustEnvironment = regularNode.getInstance(NodeRustEnvironment.class);
    try {
      final var regularSelf = regularNode.getInstance(SelfValidatorInfo.class);
      assertThat(regularSelf.key()).isEqualTo(validatorKey.getPublicKey());
      assertThat(regularSelf.bftValidatorId()).isPresent();
      assertThat(regularNode.getInstance(StateComputerLedger.class).holdsBackConsensusCommits())
          .isFalse();
      assertThat(regularNode.getInstance(VoteWithholding.class)).isSameAs(VoteWithholding.NONE);
    } finally {
      regularRustEnvironment.shutdown();
    }

    // The same key, the same genesis, the same "use the genesis" setting - as a lite node:
    final var primaryStateManagerDir = new File(primaryDir, "state_manager");
    setWritable(primaryStateManagerDir, false); // demanded by the lite start checks
    try {
      // It refuses to start: its key is the genesis validator's, and it has no validator address
      // of its own. (If it took its address from the genesis, as a regular node does, it would
      // get the genesis validator's address, see the key there as its own and start - so this
      // also covers "never from the genesis" in RadixNodeModule.)
      assertThatThrownBy(
              () ->
                  createNode(
                      keyStoreFile,
                      genesis,
                      Map.of(
                          "db.location", liteDir.getAbsolutePath(),
                          "lite.primary_state_manager_path",
                              primaryStateManagerDir.getAbsolutePath())))
          .hasRootCauseInstanceOf(LiteMustHaltException.class)
          .satisfies(
              thrown ->
                  assertThat(Throwables.getRootCause(thrown))
                      .hasMessageContaining("is registered for another validator"));

      // With a key of its own, it starts - without validator identity, and without telling
      // other nodes about its ledger state (it serves no ledger sync).
      final var ownKeyStoreFile = new File(folder.newFolder(), "keystore.ks");
      RadixKeyStore.fromFile(ownKeyStoreFile, "".toCharArray(), true)
          .writeKeyPair("node", ECKeyPair.generateNew());
      final var liteNode =
          createNode(
              ownKeyStoreFile,
              genesis,
              Map.of(
                  "db.location", folder.newFolder("lite2").getAbsolutePath(),
                  "lite.primary_state_manager_path", primaryStateManagerDir.getAbsolutePath()));
      final var liteRustEnvironment = liteNode.getInstance(NodeRustEnvironment.class);
      try {
        assertThat(liteNode.getInstance(SelfValidatorInfo.class).bftValidatorId()).isEmpty();
        assertThat(liteNode.getInstance(StateComputerLedger.class).holdsBackConsensusCommits())
            .isTrue();
        assertThat(liteNode.getInstance(VoteWithholding.class))
            .isInstanceOf(LiteStartEpochVoteWithholding.class);
        final var ledgerStatusUpdateDispatcher =
            liteNode.getInstance(
                Key.get(new TypeLiteral<RemoteEventDispatcher<NodeId, LedgerStatusUpdate>>() {}));
        assertThat(ledgerStatusUpdateDispatcher.getClass().getName())
            .startsWith(LiteDispatcherOverrideModule.class.getName());
      } finally {
        liteRustEnvironment.shutdown();
      }
    } finally {
      setWritable(primaryStateManagerDir, true); // lets the temporary folder be deleted
    }
  }
}
