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

import com.radixdlt.utils.properties.RuntimeProperties;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public final class LiteValidatorApplicationTest {
  /** Ports of its own, as every lite validator needs them. */
  private static final Map<String, String> OWN_PORTS =
      Map.of(
          "network.p2p.listen_port", "30001",
          "api.core.port", "3343",
          "api.system.port", "3344",
          "api.prometheus.port", "3345",
          "api.engine_state.port", "3346");

  private static RuntimeProperties properties(Map<String, String> overrides) throws Exception {
    final var properties = RuntimeProperties.empty();
    OWN_PORTS.forEach(properties::set);
    overrides.forEach(properties::set);
    return properties;
  }

  private static RuntimeProperties complete(Map<String, String> overrides) throws Exception {
    final var properties =
        properties(
            Map.of(
                "lite.primary_state_manager_path", "/radix/validator/data/state_manager",
                "db.location", "/var/lib/lite-b/db"));
    overrides.forEach(properties::set);
    return properties;
  }

  @Test
  public void complete_lite_configuration_is_accepted() throws Exception {
    final var properties =
        properties(
            Map.of(
                "lite.primary_state_manager_path", "/radix/validator/data/state_manager",
                "db.location", "/var/lib/lite-b/db"));
    assertThat(LiteValidatorApplication.checkConfiguration(properties)).isEmpty();
    assertThat(LiteConfig.fromRuntimeProperties(properties)).isPresent();
  }

  @Test
  public void regular_node_configuration_is_refused() throws Exception {
    final var properties = properties(Map.of("db.location", "/radix/validator/data"));
    assertThat(LiteValidatorApplication.checkConfiguration(properties))
        .anyMatch(problem -> problem.contains("only runs as a lite validator"));
    assertThat(LiteConfig.fromRuntimeProperties(properties)).isEmpty();
  }

  @Test
  public void missing_own_directory_is_refused() throws Exception {
    final var properties =
        properties(
            Map.of("lite.primary_state_manager_path", "/radix/validator/data/state_manager"));
    assertThat(LiteValidatorApplication.checkConfiguration(properties))
        .anyMatch(problem -> problem.contains("db.location is not set"));
  }

  @Test
  public void own_directory_inside_the_primary_database_directory_is_refused() throws Exception {
    // Next to the primary's state database live its safety store and address book.
    for (final var ownPath :
        new String[] {
          "/radix/validator/data",
          "/radix/validator/data/lite",
          "/radix/validator/data/state_manager",
          "/radix/validator/data/state_manager/own",
          "/radix/validator"
        }) {
      final var properties =
          properties(
              Map.of(
                  "lite.primary_state_manager_path",
                  "/radix/validator/data/state_manager",
                  "db.location",
                  ownPath));
      assertThat(LiteValidatorApplication.checkConfiguration(properties)).as(ownPath).isNotEmpty();
    }
  }

  @Test
  public void validator_address_from_the_genesis_is_refused() throws Exception {
    final var properties =
        properties(
            Map.of(
                "lite.primary_state_manager_path", "/radix/validator/data/state_manager",
                "db.location", "/var/lib/lite-b/db",
                "consensus.use_genesis_for_validator_address", "true"));
    assertThat(LiteValidatorApplication.checkConfiguration(properties))
        .anyMatch(problem -> problem.contains("only from consensus.validator_address"));
  }

  @Test
  public void validator_address_without_the_primary_address_is_refused() throws Exception {
    final var properties =
        properties(
            Map.of(
                "lite.primary_state_manager_path", "/radix/validator/data/state_manager",
                "db.location", "/var/lib/lite-b/db",
                "consensus.validator_address", "validator_rdx1example"));
    assertThat(LiteValidatorApplication.checkConfiguration(properties))
        .anyMatch(problem -> problem.contains("lite.primary_validator_address is not"));
  }

  @Test
  public void the_primary_address_as_own_address_is_refused() throws Exception {
    final var properties =
        properties(
            Map.of(
                "lite.primary_state_manager_path", "/radix/validator/data/state_manager",
                "db.location", "/var/lib/lite-b/db",
                "consensus.validator_address", "validator_rdx1example",
                "lite.primary_validator_address", "validator_rdx1EXAMPLE"));
    assertThat(LiteValidatorApplication.checkConfiguration(properties))
        .anyMatch(problem -> problem.contains("is the address of the primary"));
  }

  @Test
  public void validator_address_with_the_primary_address_is_accepted() throws Exception {
    final var properties =
        properties(
            Map.of(
                "lite.primary_state_manager_path", "/radix/validator/data/state_manager",
                "db.location", "/var/lib/lite-b/db",
                "consensus.validator_address", "validator_rdx1own",
                "lite.primary_validator_address", "validator_rdx1primary"));
    assertThat(LiteValidatorApplication.checkConfiguration(properties)).isEmpty();
  }

  @Test
  public void default_ports_of_a_node_are_refused() throws Exception {
    // Set explicitly, or left out (then the node would use them):
    for (final var key : LiteValidatorApplication.NODE_DEFAULT_PORTS.keySet()) {
      final var defaultPort = LiteValidatorApplication.NODE_DEFAULT_PORTS.get(key);
      final var set = complete(Map.of(key, defaultPort.toString()));
      assertThat(LiteValidatorApplication.checkConfiguration(set))
          .as(key)
          .anyMatch(problem -> problem.startsWith(key + " is " + defaultPort + ", a default port"));
      final var withoutKey = new HashMap<>(OWN_PORTS);
      withoutKey.put("lite.primary_state_manager_path", "/radix/validator/data/state_manager");
      withoutKey.put("db.location", "/var/lib/lite-b/db");
      withoutKey.remove(key);
      final var unset = RuntimeProperties.empty();
      withoutKey.forEach(unset::set);
      assertThat(LiteValidatorApplication.checkConfiguration(unset))
          .as(key + " not set")
          .anyMatch(problem -> problem.contains(key + " is " + defaultPort + " (not set)"));
    }
    // Another default port than the key's own one is refused as well:
    assertThat(
            LiteValidatorApplication.checkConfiguration(
                complete(Map.of("api.system.port", "3333"))))
        .anyMatch(problem -> problem.startsWith("api.system.port is 3333"));
  }

  @Test
  public void a_port_which_is_not_a_number_is_refused() throws Exception {
    assertThat(
            LiteValidatorApplication.checkConfiguration(complete(Map.of("api.core.port", "33a3"))))
        .anyMatch(problem -> problem.contains("api.core.port"));
  }

  @Test
  public void a_host_name_as_bind_address_of_a_rust_api_is_refused() throws Exception {
    for (final var key : LiteValidatorApplication.IP_ONLY_BIND_ADDRESS_KEYS) {
      assertThat(LiteValidatorApplication.checkConfiguration(complete(Map.of(key, "localhost"))))
          .as(key)
          .anyMatch(problem -> problem.startsWith(key + "=localhost is not an IP address"));
      assertThat(LiteValidatorApplication.checkConfiguration(complete(Map.of(key, "127.0.0.1"))))
          .as(key)
          .isEmpty();
      assertThat(LiteValidatorApplication.checkConfiguration(complete(Map.of(key, "::1"))))
          .as(key)
          .isEmpty();
    }
    // The Java APIs take host names:
    assertThat(
            LiteValidatorApplication.checkConfiguration(
                complete(Map.of("api.system.bind_address", "localhost"))))
        .isEmpty();
  }

  @Test
  public void an_unknown_lite_key_is_refused() throws Exception {
    assertThat(
            LiteValidatorApplication.checkConfiguration(
                complete(Map.of("lite.malloc_trim_intervall_s", "300"))))
        .anyMatch(problem -> problem.startsWith("lite.malloc_trim_intervall_s is not a setting"));
  }

  @Test
  public void every_known_lite_key_is_accepted() throws Exception {
    final var properties =
        complete(
            Map.of(
                "lite.max_open_files", "500",
                "lite.block_cache_mb", "256",
                "lite.cache_index_and_filter_blocks", "false",
                "lite.wal_links", "true",
                "lite.check_interval_ms", "50",
                "lite.min_follow_interval_ms", "500",
                "lite.follow_interval_ms", "1000",
                "lite.gate_retries", "20",
                "lite.max_consecutive_failures", "30",
                "lite.max_pending_committed", "50"));
    properties.set("lite.primary_validator_address", "validator_rdx1primary");
    properties.set("lite.malloc_trim_interval_s", "300");
    assertThat(properties.keys().stream().filter(k -> k.startsWith("lite.")))
        .containsExactlyInAnyOrderElementsOf(LiteConfig.KEYS);
    assertThat(LiteValidatorApplication.checkConfiguration(properties)).isEmpty();
  }

  @Test
  public void invalid_values_are_refused_before_the_start() throws Exception {
    assertThat(
            LiteValidatorApplication.checkConfiguration(
                complete(Map.of("lite.block_cache_mb", "abc"))))
        .anyMatch(problem -> problem.contains("lite.block_cache_mb"));
    assertThat(
            LiteValidatorApplication.checkConfiguration(
                complete(Map.of("lite.follow_interval_ms", "5"))))
        .anyMatch(problem -> problem.startsWith("lite.follow_interval_ms must be between"));
  }

  @Test
  public void memory_is_trimmed_by_default() throws Exception {
    assertThat(LiteConfig.fromRuntimeProperties(complete(Map.of())).orElseThrow())
        .extracting(LiteConfig::mallocTrimIntervalS)
        .isEqualTo(300);
  }

  @Test
  public void the_default_p2p_port_as_broadcast_port_is_a_warning() throws Exception {
    final var properties = complete(Map.of("network.p2p.broadcast_port", "30000"));
    assertThat(LiteValidatorApplication.warnings(properties))
        .anyMatch(warning -> warning.startsWith("network.p2p.broadcast_port=30000"));
    assertThat(LiteValidatorApplication.checkConfiguration(properties)).isEmpty();
    assertThat(LiteValidatorApplication.warnings(complete(Map.of()))).isEmpty();
  }
}
