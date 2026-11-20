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

package com.radixdlt;

// Modified in 2026 by michael-rmp for the lite validator (babylon-node-lite), see NOTICE.

import com.google.common.base.Stopwatch;
import com.radixdlt.bootstrap.RadixNodeBootstrapper;
import com.radixdlt.lite.LiteConfig;
import com.radixdlt.lite.LiteMustHaltException;
import com.radixdlt.lite.LiteValidatorApplication;
import com.radixdlt.monitoring.ApplicationVersion;
import com.radixdlt.utils.MemoryLeakDetector;
import com.radixdlt.utils.properties.RuntimeProperties;
import java.net.URISyntaxException;
import java.util.List;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class RadixNodeApplication {
  private static final Logger log = LogManager.getLogger();

  private RadixNodeApplication() {}

  static {
    System.setProperty("java.net.preferIPv4Stack", "true");
  }

  public static void main(String[] args) {
    run(
        args,
        properties ->
            LiteConfig.fromRuntimeProperties(properties).isPresent()
                ? List.of(
                    "%s is set, which makes this a lite validator configuration: start it with %s"
                        .formatted(
                            LiteConfig.PRIMARY_STATE_MANAGER_PATH_KEY,
                            LiteValidatorApplication.class.getName()))
                : List.of());
  }

  /**
   * Starts a node with the configuration from the given command line, unless the given check finds
   * a reason not to.
   *
   * @param configurationCheck returns the reasons why this entry point must not start the given
   *     configuration (empty if fine)
   */
  public static void run(
      String[] args, Function<RuntimeProperties, List<String>> configurationCheck) {
    try {
      MemoryLeakDetector.start();
      logVersion();
      dumpExecutionLocation();
      final var properties = RuntimeProperties.fromCommandLineArgs(args);
      final var problems = configurationCheck.apply(properties);
      if (!problems.isEmpty()) {
        problems.forEach(problem -> log.fatal("Refusing to start: {}", problem));
        LogManager.shutdown(); // Flush any async logs
        // A configuration error stays until the operator fixes it: no automatic restart.
        System.exit(LiteMustHaltException.EXIT_CODE);
      }
      final var nodeBootStopwatch = Stopwatch.createStarted();
      final var radixNode = RadixNodeBootstrapper.runNewNode(properties);
      final var startupTime = nodeBootStopwatch.elapsed();
      log.info(
          "Radix node {} started successfully in {} ms",
          radixNode.selfDetailedString(),
          startupTime.toMillis());
      radixNode.reportSelfStartupTime(startupTime);
      Runtime.getRuntime().addShutdownHook(new Thread(radixNode::shutdown, "Node-Shutdown"));
    } catch (Exception ex) {
      final var halt = liteMustHaltCause(ex);
      if (halt != null) {
        // A lite validator found a condition which a restart would not fix (see the exception).
        log.fatal(
            "Refusing to start, and must not be restarted automatically: {}", halt.getMessage());
        LogManager.shutdown(); // Flush any async logs
        System.exit(LiteMustHaltException.EXIT_CODE);
      }
      log.fatal("Unable to start", ex);
      LogManager.shutdown(); // Flush any async logs
      System.exit(-1);
    }
  }

  private static LiteMustHaltException liteMustHaltCause(Throwable throwable) {
    for (var cause = throwable; cause != null; cause = cause.getCause()) {
      if (cause instanceof LiteMustHaltException halt) {
        return halt;
      }
    }
    return null;
  }

  private static void logVersion() {
    log.always()
        .log(
            "Radix distributed ledger '{}' from branch '{}' commit '{}'",
            ApplicationVersion.INSTANCE.display(),
            ApplicationVersion.INSTANCE.branch(),
            ApplicationVersion.INSTANCE.commit());
  }

  private static void dumpExecutionLocation() {
    try {
      String jarFile =
          RadixNodeApplication.class
              .getProtectionDomain()
              .getCodeSource()
              .getLocation()
              .toURI()
              .getPath();
      System.setProperty("radix.jar", jarFile);

      String jarPath = jarFile;

      if (jarPath.toLowerCase().endsWith(".jar")) {
        jarPath = jarPath.substring(0, jarPath.lastIndexOf('/'));
      }
      System.setProperty("radix.jar.path", jarPath);

      log.debug("Execution file: {}", System.getProperty("radix.jar"));
      log.debug("Execution path: {}", System.getProperty("radix.jar.path"));
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Error while fetching execution location", e);
    }
  }
}
