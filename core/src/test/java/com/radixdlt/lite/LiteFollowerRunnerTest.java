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

import static org.mockito.Mockito.*;

import com.radixdlt.environment.FatalPanicHandler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class LiteFollowerRunnerTest {
  private final LiteLedgerFollower follower = mock(LiteLedgerFollower.class);
  private final FatalPanicHandler stopper = mock(FatalPanicHandler.class);
  private final Runnable halt = mock(Runnable.class);
  private final AtomicLong nanos = new AtomicLong(Long.MAX_VALUE - 5); // the clock may wrap
  private final LiteFollowerRunner runner =
      new LiteFollowerRunner(follower, stopper, halt, 3, 1000, nanos::get);

  private void advanceClockMs(long ms) {
    nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
  }

  @Test
  public void every_tick_follows_while_there_are_no_failures() {
    runner.tick();
    runner.tick();
    runner.tick();
    verify(follower, times(3)).followOnce();
    verifyNoInteractions(stopper);
  }

  @Test
  public void after_a_failure_the_next_attempt_waits_for_the_retry_delay() {
    when(follower.followOnce()).thenThrow(new LiteRetryLaterException("no")).thenReturn(0);

    runner.tick(); // fails
    advanceClockMs(50);
    runner.tick(); // too early
    advanceClockMs(900);
    runner.tick(); // too early
    verify(follower, times(1)).followOnce();

    advanceClockMs(50);
    runner.tick(); // succeeds
    runner.tick(); // and the ticks count again
    verify(follower, times(3)).followOnce();
    verifyNoInteractions(stopper);
  }

  @Test
  public void frequent_ticks_do_not_use_up_the_allowed_failures() {
    when(follower.followOnce()).thenThrow(new LiteRetryLaterException("no"));

    for (int i = 0; i < 100; i++) {
      runner.tick();
      advanceClockMs(5);
    }
    verify(follower, times(1)).followOnce();
    verifyNoInteractions(stopper);

    advanceClockMs(1000);
    runner.tick();
    verifyNoInteractions(stopper);
    advanceClockMs(1000);
    runner.tick(); // the third failure in a row
    verify(follower, times(3)).followOnce();
    verify(stopper).handleFatalPanic();

    advanceClockMs(5000);
    runner.tick(); // stopped for good
    verify(follower, times(3)).followOnce();
  }

  @Test
  public void a_must_stop_failure_stops_at_once() {
    when(follower.followOnce()).thenThrow(new LiteMustStopException("diverged"));
    runner.tick();
    verify(stopper).handleFatalPanic();
  }

  @Test
  public void a_halt_ends_following_for_good_without_the_fatal_panic_handler() {
    when(follower.followOnce()).thenThrow(new LiteMustHaltException("identity"));
    runner.tick();
    advanceClockMs(10_000);
    runner.tick();
    verify(follower, times(1)).followOnce();
    verify(halt, times(1)).run();
    verifyNoInteractions(stopper);
  }
}
