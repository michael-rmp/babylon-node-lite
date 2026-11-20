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

import com.google.common.reflect.TypeToken;
import com.radixdlt.environment.NodeRustEnvironment;
import com.radixdlt.lang.Option;
import com.radixdlt.lang.Result;
import com.radixdlt.lang.Tuple;
import com.radixdlt.lang.Tuple.Tuple0;
import com.radixdlt.lang.Tuple.Tuple3;
import com.radixdlt.monitoring.Metrics;
import com.radixdlt.monitoring.Metrics.MethodId;
import com.radixdlt.sbor.Natives;
import com.radixdlt.statecomputer.commit.LedgerHashes;
import com.radixdlt.utils.UInt32;
import com.radixdlt.utils.UInt64;

/**
 * Lite validator: moves the view of the primary's state database forward. Only valid for an
 * environment created by {@link NodeRustEnvironment#createLite}.
 */
public final class RustLiteFollower {
  private final Natives.Call1<Tuple3<UInt32, UInt32, UInt32>, Result<UInt64, LiteCatchUpError>>
      catchUpFunc;
  private final Natives.Call1<Tuple0, Boolean> trimMemoryFunc;
  private final Natives.Call1<UInt64, Option<LedgerHashes>> ledgerHashesAtFunc;

  public RustLiteFollower(Metrics metrics, NodeRustEnvironment nodeRustEnvironment) {
    final var timer = metrics.stateManager().nativeCall();
    this.catchUpFunc =
        Natives.builder(nodeRustEnvironment, RustLiteFollower::catchUp)
            .measure(timer.label(new MethodId(RustLiteFollower.class, "catchUp")))
            .build(new TypeToken<>() {});
    this.trimMemoryFunc =
        Natives.builder(nodeRustEnvironment, RustLiteFollower::trimMemory)
            .measure(timer.label(new MethodId(RustLiteFollower.class, "trimMemory")))
            .build(new TypeToken<>() {});
    this.ledgerHashesAtFunc =
        Natives.builder(nodeRustEnvironment, RustLiteFollower::ledgerHashesAt)
            .measure(timer.label(new MethodId(RustLiteFollower.class, "ledgerHashesAt")))
            .build(new TypeToken<>() {});
  }

  /**
   * Catches up with the primary and verifies the new view. All database snapshots must have been
   * released for this to proceed, and no {@code prepare} may run concurrently (i.e. call this under
   * the ledger lock).
   *
   * <p>A catch-up is expensive even when the primary has written nothing. So this may be called
   * often: while the files of the primary look unchanged, the catch-up is postponed (and the
   * current view is returned), but never for longer than the given time since the last one. And
   * however the files look, no catch-up starts sooner than the minimum interval after the last.
   *
   * @param gateRetries how often to catch up again if the view is not consistent
   * @param minIntervalMs the minimum time between two catch-ups
   * @param maxPostponementMs for how long a catch-up may be postponed; with 0 (and no minimum
   *     interval) every call catches up
   * @return the top state version of the current view
   */
  public Result<UInt64, LiteCatchUpError> catchUp(
      int gateRetries, int minIntervalMs, int maxPostponementMs) {
    return this.catchUpFunc.call(
        Tuple.tuple(
            UInt32.fromNonNegativeInt(gateRetries),
            UInt32.fromNonNegativeInt(minIntervalMs),
            UInt32.fromNonNegativeInt(maxPostponementMs)));
  }

  /**
   * Returns the free memory of the C allocator to the system ({@code malloc_trim}). Independent of
   * the ledger: needs no lock.
   *
   * @return whether any memory was released
   */
  public boolean trimMemory() {
    return this.trimMemoryFunc.call(Tuple.tuple());
  }

  /**
   * Returns the ledger hashes right after the given state version, as the current view of the
   * primary's database shows them (empty if the view does not reach that state version yet).
   */
  public Option<LedgerHashes> ledgerHashesAt(long stateVersion) {
    return this.ledgerHashesAtFunc.call(UInt64.fromNonNegativeLong(stateVersion));
  }

  private static native byte[] ledgerHashesAt(
      NodeRustEnvironment nodeRustEnvironment, byte[] payload);

  private static native byte[] trimMemory(NodeRustEnvironment nodeRustEnvironment, byte[] payload);

  private static native byte[] catchUp(NodeRustEnvironment nodeRustEnvironment, byte[] payload);
}
