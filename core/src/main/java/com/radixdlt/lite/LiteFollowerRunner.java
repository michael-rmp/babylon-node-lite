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

import com.radixdlt.environment.FatalPanicHandler;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Lite validator: runs the {@link LiteLedgerFollower} periodically and decides what a failure
 * means. Stopping is always safe: nothing but this process is affected.
 */
public final class LiteFollowerRunner {
  private static final Logger log = LogManager.getLogger();

  private final LiteLedgerFollower follower;
  private final FatalPanicHandler stopper;
  private final Runnable halt;
  private final int maxConsecutiveFailures;
  private final long retryDelayNanos;
  private final LongSupplier nanoClock;
  private long retryNotBeforeNanos;
  private int consecutiveFailures = 0;
  private boolean stopped = false;

  /**
   * @param halt ends the process for good (see {@link LiteMustHaltException})
   * @param retryDelayMs after a failed attempt, the next one is made no earlier than this (the
   *     ticks come much more often, and would otherwise use up the allowed failures in no time)
   */
  public LiteFollowerRunner(
      LiteLedgerFollower follower,
      FatalPanicHandler stopper,
      Runnable halt,
      int maxConsecutiveFailures,
      long retryDelayMs) {
    this(follower, stopper, halt, maxConsecutiveFailures, retryDelayMs, System::nanoTime);
  }

  LiteFollowerRunner(
      LiteLedgerFollower follower,
      FatalPanicHandler stopper,
      Runnable halt,
      int maxConsecutiveFailures,
      long retryDelayMs,
      LongSupplier nanoClock) {
    this.follower = Objects.requireNonNull(follower);
    this.stopper = Objects.requireNonNull(stopper);
    this.halt = Objects.requireNonNull(halt);
    this.maxConsecutiveFailures = maxConsecutiveFailures;
    this.retryDelayNanos = TimeUnit.MILLISECONDS.toNanos(retryDelayMs);
    this.nanoClock = Objects.requireNonNull(nanoClock);
  }

  /** One attempt to follow the primary's ledger. Never throws. */
  public synchronized void tick() {
    if (this.stopped) {
      return;
    }
    if (this.consecutiveFailures > 0 && this.nanoClock.getAsLong() - this.retryNotBeforeNanos < 0) {
      return;
    }
    try {
      this.follower.followOnce();
      if (this.consecutiveFailures > 0) {
        log.info(
            "Lite validator follows the primary's ledger again after {} failed attempt(s)",
            this.consecutiveFailures);
      }
      this.consecutiveFailures = 0;
    } catch (LiteRetryLaterException e) {
      this.consecutiveFailures++;
      this.retryNotBeforeNanos = this.nanoClock.getAsLong() + this.retryDelayNanos;
      log.warn(
          "Lite validator could not follow the primary's ledger (attempt {} of {}): {}",
          this.consecutiveFailures,
          this.maxConsecutiveFailures,
          e.getMessage());
      if (this.consecutiveFailures >= this.maxConsecutiveFailures) {
        stop("Too many failed attempts in a row; a restart re-opens the primary's database.");
      }
    } catch (LiteMustHaltException e) {
      this.stopped = true;
      log.error("Lite validator halts and must not be restarted automatically: {}", e.getMessage());
      this.halt.run();
    } catch (LiteMustStopException e) {
      stop(e.getMessage());
    } catch (RuntimeException e) {
      log.error("Unexpected failure while following the primary's ledger", e);
      stop("Unexpected failure: " + e);
    }
  }

  private void stop(String reason) {
    this.stopped = true;
    log.error("Lite validator stops: {}", reason);
    this.stopper.handleFatalPanic();
  }
}
