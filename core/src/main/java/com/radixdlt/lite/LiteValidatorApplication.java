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

import com.google.common.net.InetAddresses;
import com.radixdlt.RadixNodeApplication;
import com.radixdlt.utils.properties.RuntimeProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.apache.logging.log4j.LogManager;

/**
 * The entry point of a lite validator: a node which only reads the ledger database of another node
 * running on the same machine (see {@link LiteConfig}).
 *
 * <p>This entry point never starts a regular node: without a complete lite configuration it refuses
 * to start. (And the regular entry point, {@link RadixNodeApplication}, refuses a lite
 * configuration.) So a mix-up of start commands or configuration files cannot make a process open a
 * ledger database for writing by accident.
 */
public final class LiteValidatorApplication {
  /**
   * The ports a node binds by default. The validator whose database a lite validator reads runs on
   * the same machine and usually uses them: a lite validator with one of them would either fail to
   * start or, if it starts first (e.g. after a reboot), keep the validator from starting.
   */
  static final Map<String, Integer> NODE_DEFAULT_PORTS = nodeDefaultPorts();

  /** The APIs served by Rust code, which accept only an IP address to bind to (no host name). */
  static final List<String> IP_ONLY_BIND_ADDRESS_KEYS =
      List.of("api.core.bind_address", "api.engine_state.bind_address");

  private LiteValidatorApplication() {}

  private static Map<String, Integer> nodeDefaultPorts() {
    final var ports = new LinkedHashMap<String, Integer>();
    ports.put("network.p2p.listen_port", 30000);
    ports.put("api.core.port", 3333);
    ports.put("api.system.port", 3334);
    ports.put("api.prometheus.port", 3335);
    ports.put("api.engine_state.port", 3336);
    return ports;
  }

  public static void main(String[] args) {
    RadixNodeApplication.run(
        args,
        properties -> {
          final var log = LogManager.getLogger(LiteValidatorApplication.class);
          warnings(properties).forEach(warning -> log.warn("Lite validator: {}", warning));
          return checkConfiguration(properties);
        });
  }

  /** Returns what looks wrong in the given configuration, but does not prevent a start. */
  public static List<String> warnings(RuntimeProperties properties) {
    final var warnings = new ArrayList<String>();
    final var p2pDefault = NODE_DEFAULT_PORTS.get("network.p2p.listen_port");
    final var broadcastPort = properties.get("network.p2p.broadcast_port", "").trim();
    if (broadcastPort.equals(p2pDefault.toString())) {
      warnings.add(
          ("network.p2p.broadcast_port=%s is the default port of a node: other nodes will try to"
                  + " reach this lite validator there, which is usually the validator on the same"
                  + " machine (fine only if a port forwarding sends it to this process)")
              .formatted(broadcastPort));
    }
    return warnings;
  }

  /** Returns the reasons why the given configuration must not be started as a lite validator. */
  public static List<String> checkConfiguration(RuntimeProperties properties) {
    final var problems = new ArrayList<String>();

    final var primaryPath = properties.get(LiteConfig.PRIMARY_STATE_MANAGER_PATH_KEY, "");
    if (primaryPath.isBlank()) {
      problems.add(
          "%s is not set: this program only runs as a lite validator"
              .formatted(LiteConfig.PRIMARY_STATE_MANAGER_PATH_KEY));
    }

    final var ownPath = properties.get("db.location", "");
    if (ownPath.isBlank()) {
      problems.add(
          "db.location is not set: a lite validator needs an explicitly configured directory of"
              + " its own");
    }

    if (!primaryPath.isBlank() && !ownPath.isBlank()) {
      final var primary = Path.of(primaryPath).toAbsolutePath().normalize();
      final var own = Path.of(ownPath).toAbsolutePath().normalize();
      if (own.startsWith(primary) || primary.startsWith(own)) {
        problems.add(
            "db.location (%s) and %s (%s) must not contain each other"
                .formatted(own, LiteConfig.PRIMARY_STATE_MANAGER_PATH_KEY, primary));
      }
      // The primary keeps its other databases next to its state database:
      if (primary.getParent() != null && own.startsWith(primary.getParent())) {
        problems.add(
            "db.location (%s) must not be inside the primary node's database directory (%s)"
                .formatted(own, primary.getParent()));
      }
    }

    // A lite validator takes part in consensus only with an explicitly configured address of its
    // own, never one resolved from the genesis (which looks the address up by key), and only if
    // it knows whose database it reads (see LiteIdentityGuard).
    if (properties.get("consensus.use_genesis_for_validator_address", false)) {
      problems.add(
          "consensus.use_genesis_for_validator_address=true: a lite validator takes its validator"
              + " address only from consensus.validator_address");
    }
    final var validatorAddress = properties.get("consensus.validator_address", "").trim();
    final var primaryValidatorAddress = properties.get("lite.primary_validator_address", "").trim();
    if (!validatorAddress.isEmpty()) {
      if (primaryValidatorAddress.isEmpty()) {
        problems.add(
            "consensus.validator_address is set, but lite.primary_validator_address is not: a lite"
                + " validator must know the validator whose database it reads");
      } else if (primaryValidatorAddress.equalsIgnoreCase(validatorAddress)) {
        problems.add(
            "consensus.validator_address is the address of the primary"
                + " (lite.primary_validator_address): a lite validator needs a validator of its"
                + " own");
      }
    }

    // Ports and addresses to bind to:
    final var defaultPorts = new TreeSet<>(NODE_DEFAULT_PORTS.values());
    NODE_DEFAULT_PORTS.forEach(
        (key, defaultPort) -> {
          final int port;
          try {
            port = properties.get(key, defaultPort);
          } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
            return;
          }
          if (defaultPorts.contains(port)) {
            problems.add(
                ("%s is %d%s, a default port of a node %s: the validator on this machine needs"
                        + " it; give the lite validator ports of its own (e.g. 30001 and"
                        + " 3343-3346)")
                    .formatted(
                        key, port, properties.get(key) == null ? " (not set)" : "", defaultPorts));
          }
        });
    for (final var key : IP_ONLY_BIND_ADDRESS_KEYS) {
      final var address = properties.get(key);
      if (address != null && !InetAddresses.isInetAddress(address.trim())) {
        problems.add(
            "%s=%s is not an IP address (e.g. 127.0.0.1): this API would not start"
                .formatted(key, address));
      }
    }

    // Unknown keys of a lite validator are mistakes (e.g. a typo, which would silently leave a
    // setting at its default):
    for (final var key : new TreeSet<>(properties.keys())) {
      if (key.startsWith("lite.") && !LiteConfig.KEYS.contains(key)) {
        problems.add(
            "%s is not a setting of a lite validator (a typo?); the settings are: %s"
                .formatted(key, new TreeSet<>(LiteConfig.KEYS)));
      }
    }

    // Values out of range or not parsable:
    if (!primaryPath.isBlank()) {
      try {
        LiteConfig.fromRuntimeProperties(properties);
      } catch (IllegalArgumentException e) {
        problems.add(e.getMessage());
      }
    }
    return problems;
  }
}
