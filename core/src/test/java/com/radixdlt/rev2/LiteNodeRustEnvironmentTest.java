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

package com.radixdlt.rev2;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.radixdlt.environment.*;
import com.radixdlt.exceptions.RustPanicException;
import com.radixdlt.genesis.GenesisData;
import com.radixdlt.genesis.RawGenesisDataWithHash;
import com.radixdlt.lang.Option;
import com.radixdlt.lite.RustLiteFollower;
import com.radixdlt.mempool.RustMempoolConfig;
import com.radixdlt.monitoring.MetricsInitializer;
import com.radixdlt.protocol.ProtocolConfig;
import com.radixdlt.statecomputer.commit.LedgerProof;
import com.radixdlt.transaction.LedgerSyncLimitsConfig;
import com.radixdlt.transaction.REv2TransactionAndProofStore;
import java.io.File;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Lite validator: the Rust environment which only reads another node's state database. */
public final class LiteNodeRustEnvironmentTest {
  @Rule public TemporaryFolder folder = new TemporaryFolder();

  private static NodeRustEnvironment createLite(File primaryStateManagerDir, File ownDir) {
    final var config =
        new StateManagerConfig(
            NetworkDefinition.INT_TEST_NET,
            Option.some(new RustMempoolConfig(10 * 1024 * 1024, 20)),
            Option.none(),
            new DatabaseBackendConfig(primaryStateManagerDir.getPath()),
            new DatabaseConfig(false, false, false, false, true),
            LoggingConfig.getDefault(),
            StateTreeGcConfig.forTesting(),
            LedgerProofsGcConfig.forTesting(),
            LedgerSyncLimitsConfig.defaults(),
            ProtocolConfig.testingDefault(),
            false,
            ScenariosExecutionConfig.NONE);
    return NodeRustEnvironment.createLite(
        RawGenesisDataWithHash.fromGenesisData(GenesisData.testingDefaultEmpty()),
        tx -> {},
        () -> {},
        config,
        ownDir.getPath(),
        100,
        16,
        false,
        true);
  }

  private static void setWritable(File dir, boolean writable) {
    for (final var file : dir.listFiles()) {
      assertTrue(file.setWritable(writable, false));
    }
    assertTrue(dir.setWritable(writable, false));
  }

  @Test
  public void lite_environment_reads_a_read_only_database_and_refuses_a_writable_one()
      throws Exception {
    final var primaryDir = folder.newFolder("primary");
    final var ownDir = new File(folder.getRoot(), "own");
    final var stateManagerDir = new File(primaryDir, "state_manager");

    // A regular node runs genesis and stops:
    final LedgerProof expectedProof;
    try (var primary = NodeRustEnvironmentHelper.createNodeRustEnvironment(primaryDir.getPath())) {
      expectedProof =
          new REv2TransactionAndProofStore(new MetricsInitializer().initialize(), primary)
              .getLatestProof()
              .orElseThrow();
    }
    final var primaryFilesBefore = Arrays.asList(stateManagerDir.list());

    // Its database is writable for us: the lite environment must refuse to start.
    assertThatThrownBy(() -> createLite(stateManagerDir, ownDir))
        .isInstanceOf(RustPanicException.class)
        .hasMessageContaining("lite validator start refused")
        .hasMessageContaining("writable for this process");
    assertFalse(ownDir.exists());

    // A missing database is refused as well, and nothing gets created in its place:
    final var missingDir = new File(folder.getRoot(), "missing");
    assertThatThrownBy(() -> createLite(missingDir, ownDir))
        .isInstanceOf(RustPanicException.class)
        .hasMessageContaining("no CURRENT file");
    assertFalse(missingDir.exists());

    // Read-only (here: by permissions), it opens and shows the same ledger:
    setWritable(stateManagerDir, false);
    try (var lite = createLite(stateManagerDir, ownDir)) {
      final var proof =
          new REv2TransactionAndProofStore(new MetricsInitializer().initialize(), lite)
              .getLatestProof()
              .orElseThrow();
      assertEquals(expectedProof, proof);

      // Catching up yields the same top state version (the stopped node wrote nothing more):
      final var follower = new RustLiteFollower(new MetricsInitializer().initialize(), lite);
      assertEquals(expectedProof.stateVersion(), follower.catchUp(0, 0, 0).unwrap().toLong());
      assertEquals(expectedProof.stateVersion(), follower.catchUp(3, 0, 60_000).unwrap().toLong());

      // The point read for the comparison with the own consensus (phase 5):
      assertEquals(
          expectedProof.ledgerHeader().hashes(),
          follower.ledgerHashesAt(expectedProof.stateVersion()).unwrap());
      assertTrue(follower.ledgerHashesAt(expectedProof.stateVersion() + 1).isEmpty());
    } finally {
      setWritable(stateManagerDir, true); // lets the TemporaryFolder clean up
    }

    // The lite environment's files are in its own directory; the primary's are untouched:
    assertTrue(new File(ownDir, "state_manager_secondary").isDirectory());
    assertTrue(new File(ownDir, "consensus_safety_store").isDirectory());
    assertTrue(new File(ownDir, "address_book").isDirectory());
    assertEquals(primaryFilesBefore, Arrays.asList(stateManagerDir.list()));
  }
}
