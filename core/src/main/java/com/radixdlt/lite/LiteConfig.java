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

import com.google.common.base.Preconditions;
import com.radixdlt.utils.properties.RuntimeProperties;
import java.util.Optional;
import java.util.Set;

/**
 * The configuration of a lite validator. A node is a lite validator if and only if {@link
 * #PRIMARY_STATE_MANAGER_PATH_KEY} is set.
 *
 * @param primaryStateManagerPath the {@code state_manager} directory of the primary node, which is
 *     only read (deliberately a key of its own, and not {@code db.location}, which is this node's
 *     own, writable directory)
 * @param maxOpenFiles RocksDB's {@code max_open_files} for the primary's database; every open file
 *     costs memory (~0.4 MB on mainnet), so "all files" (-1) is not an option there
 * @param blockCacheMb capacity of the one block cache shared by all column families of the
 *     primary's database
 * @param cacheIndexAndFilterBlocks whether index and filter blocks live in that cache (and are
 *     bounded by its capacity, at the price of re-reading evicted ones) instead of staying in
 *     memory for as long as their file is open
 * @param walLinks whether to keep links to the primary's WAL files in the own directory; without
 *     them, every catch-up lists the primary's whole database directory (~100 ms on mainnet)
 * @param followIntervalMs how often to look for new commits in the primary's database
 * @param gateRetries how often to catch up again when the database shows an inconsistent state
 * @param maxConsecutiveFailures after how many failed attempts in a row this process stops
 * @param maxPendingCommitted above this number of vertices which this node's consensus has
 *     committed, but the primary's database does not show yet, this node does not vote
 * @param primaryValidatorAddress the validator address of the primary, if it is a validator ("" =
 *     not set); checked against this process' identity (see {@link LiteIdentityGuard})
 * @param mallocTrimIntervalS how often to return the free memory of the C allocator to the system
 *     ({@code malloc_trim}); 0 = never (default 300)
 */
public record LiteConfig(
    String primaryStateManagerPath,
    int maxOpenFiles,
    int blockCacheMb,
    boolean cacheIndexAndFilterBlocks,
    boolean walLinks,
    long checkIntervalMs,
    long minFollowIntervalMs,
    long followIntervalMs,
    int gateRetries,
    int maxConsecutiveFailures,
    int maxPendingCommitted,
    String primaryValidatorAddress,
    int mallocTrimIntervalS) {
  public static final String PRIMARY_STATE_MANAGER_PATH_KEY = "lite.primary_state_manager_path";

  /** All configuration keys of a lite validator; any other {@code lite.*} key is a mistake. */
  public static final Set<String> KEYS =
      Set.of(
          PRIMARY_STATE_MANAGER_PATH_KEY,
          "lite.max_open_files",
          "lite.block_cache_mb",
          "lite.cache_index_and_filter_blocks",
          "lite.wal_links",
          "lite.check_interval_ms",
          "lite.min_follow_interval_ms",
          "lite.follow_interval_ms",
          "lite.gate_retries",
          "lite.max_consecutive_failures",
          "lite.max_pending_committed",
          "lite.primary_validator_address",
          "lite.malloc_trim_interval_s");

  public static Optional<LiteConfig> fromRuntimeProperties(RuntimeProperties properties) {
    final var primaryStateManagerPath = properties.get(PRIMARY_STATE_MANAGER_PATH_KEY, "");
    if (primaryStateManagerPath.isBlank()) {
      return Optional.empty();
    }
    final var maxOpenFiles = properties.get("lite.max_open_files", 500);
    final var blockCacheMb = properties.get("lite.block_cache_mb", 256);
    final var cacheIndexAndFilterBlocks =
        properties.get("lite.cache_index_and_filter_blocks", false);
    final var walLinks = properties.get("lite.wal_links", true);
    // How often to look whether the primary has written anything (cheap), how much time must lie
    // between two catch-ups (expensive on a large database), and after which time to catch up
    // even if the primary does not look changed:
    final var checkIntervalMs = properties.get("lite.check_interval_ms", 50L);
    final var minFollowIntervalMs = properties.get("lite.min_follow_interval_ms", 500L);
    final var followIntervalMs = properties.get("lite.follow_interval_ms", 1000L);
    final var gateRetries = properties.get("lite.gate_retries", 20);
    final var maxConsecutiveFailures = properties.get("lite.max_consecutive_failures", 30);
    final var maxPendingCommitted = properties.get("lite.max_pending_committed", 50);
    final var primaryValidatorAddress = properties.get("lite.primary_validator_address", "").trim();
    // Without regular trimming, freed native memory stays with the process (measured: ~0.6–0.9 GB
    // more, growing over hours on a large database):
    final var mallocTrimIntervalS = properties.get("lite.malloc_trim_interval_s", 300);
    Preconditions.checkArgument(
        maxOpenFiles == -1 || maxOpenFiles >= 20,
        "lite.max_open_files must be -1 or at least 20: %s",
        maxOpenFiles);
    Preconditions.checkArgument(
        blockCacheMb >= 8, "lite.block_cache_mb must be at least 8: %s", blockCacheMb);
    Preconditions.checkArgument(
        checkIntervalMs >= 10, "lite.check_interval_ms must be at least 10: %s", checkIntervalMs);
    Preconditions.checkArgument(
        minFollowIntervalMs >= 0,
        "lite.min_follow_interval_ms must not be negative: %s",
        minFollowIntervalMs);
    Preconditions.checkArgument(
        followIntervalMs >= Math.max(checkIntervalMs, minFollowIntervalMs)
            && followIntervalMs <= 60_000,
        "lite.follow_interval_ms must be between lite.check_interval_ms (%s),"
            + " lite.min_follow_interval_ms (%s) and 60000: %s",
        checkIntervalMs,
        minFollowIntervalMs,
        followIntervalMs);
    Preconditions.checkArgument(
        gateRetries >= 0, "lite.gate_retries must not be negative: %s", gateRetries);
    Preconditions.checkArgument(
        maxConsecutiveFailures >= 1,
        "lite.max_consecutive_failures must be positive: %s",
        maxConsecutiveFailures);
    Preconditions.checkArgument(
        maxPendingCommitted >= 1,
        "lite.max_pending_committed must be positive: %s",
        maxPendingCommitted);
    Preconditions.checkArgument(
        mallocTrimIntervalS >= 0 && mallocTrimIntervalS <= 86_400,
        "lite.malloc_trim_interval_s must be between 0 and 86400: %s",
        mallocTrimIntervalS);
    return Optional.of(
        new LiteConfig(
            primaryStateManagerPath,
            maxOpenFiles,
            blockCacheMb,
            cacheIndexAndFilterBlocks,
            walLinks,
            checkIntervalMs,
            minFollowIntervalMs,
            followIntervalMs,
            gateRetries,
            maxConsecutiveFailures,
            maxPendingCommitted,
            primaryValidatorAddress,
            mallocTrimIntervalS));
  }
}
