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

import com.radixdlt.consensus.bft.BFTValidatorSet;
import com.radixdlt.crypto.ECDSASecp256k1PublicKey;
import com.radixdlt.rev2.ComponentAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Lite validator: makes sure that this process never acts with the identity of another validator,
 * in particular not with the one of the primary (whose database it reads). Checked against the
 * validator set on the ledger, at start and with every epoch change; no file of the primary is
 * read.
 *
 * <p>A violation means: the key of this process is registered for a validator which is not this
 * process' own one (e.g. the primary's keystore was copied into the lite validator's directory), or
 * this process is configured with the primary's validator address.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public final class LiteIdentityGuard {
  private final ECDSASecp256k1PublicKey selfKey;
  private final Optional<ComponentAddress> selfAddress;
  private final Optional<ComponentAddress> primaryAddress;
  private final Function<ComponentAddress, String> format;
  private boolean notTakingPart = false;

  /**
   * @param selfKey the key of this process
   * @param selfAddress the validator address of this process, if it is configured as a validator
   * @param primaryAddress the validator address of the primary ({@code
   *     lite.primary_validator_address}), if configured
   */
  public LiteIdentityGuard(
      ECDSASecp256k1PublicKey selfKey,
      Optional<ComponentAddress> selfAddress,
      Optional<ComponentAddress> primaryAddress) {
    this(selfKey, selfAddress, primaryAddress, ComponentAddress::toString);
  }

  /**
   * @param format how addresses appear in a violation (the node passes the network's bech32
   *     encoding, as in the configuration)
   */
  public LiteIdentityGuard(
      ECDSASecp256k1PublicKey selfKey,
      Optional<ComponentAddress> selfAddress,
      Optional<ComponentAddress> primaryAddress,
      Function<ComponentAddress, String> format) {
    this.format = Objects.requireNonNull(format);
    this.selfKey = Objects.requireNonNull(selfKey);
    this.selfAddress = Objects.requireNonNull(selfAddress);
    this.primaryAddress = Objects.requireNonNull(primaryAddress);
  }

  /** Returns why this process must not run with the given validator set, if it must not. */
  public Optional<String> violation(BFTValidatorSet validatorSet) {
    if (this.selfAddress.isPresent() && this.selfAddress.equals(this.primaryAddress)) {
      return Optional.of(
          "the configured validator address is the primary's (lite.primary_validator_address): "
              + this.format.apply(this.selfAddress.get()));
    }
    for (var validator : validatorSet.getValidators()) {
      final var id = validator.getValidatorId();
      if (id.getKey().equals(this.selfKey)
          && !this.selfAddress.equals(Optional.of(id.getValidatorAddress()))) {
        final var whose =
            this.primaryAddress.equals(Optional.of(id.getValidatorAddress()))
                ? "the primary's validator"
                : "another validator";
        return Optional.of(
            "the key of this process is registered for %s (%s) in the current validator set"
                .formatted(whose, this.format.apply(id.getValidatorAddress())));
      }
    }
    return Optional.empty();
  }

  /**
   * Returns why this process does not take part in consensus with the given validator set although
   * it is configured as a validator, if so: its validator address is registered with another key.
   *
   * <p>Not a violation: an operator moves a validator between machines by changing its key on
   * ledger (e.g. for maintenance); this process then only follows, and takes part again once its
   * own key is registered again (from the next epoch on). See {@link #reportParticipation}.
   */
  public Optional<String> notTakingPart(BFTValidatorSet validatorSet) {
    if (this.selfAddress.isEmpty()) {
      return Optional.empty();
    }
    for (var validator : validatorSet.getValidators()) {
      final var id = validator.getValidatorId();
      if (id.getValidatorAddress().equals(this.selfAddress.get())
          && !id.getKey().equals(this.selfKey)) {
        return Optional.of(
            ("the configured validator %s is registered with key %s, this process has key %s:"
                    + " it only follows (normal while the validator runs elsewhere, e.g. during"
                    + " maintenance; otherwise check consensus.validator_address and the key)")
                .formatted(
                    this.format.apply(this.selfAddress.get()),
                    id.getKey().toHex(),
                    this.selfKey.toHex()));
      }
    }
    return Optional.empty();
  }

  /**
   * Returns what to log about taking part in consensus with the given validator set: something only
   * at the first call if this process does not take part, and later only when that changes.
   */
  public synchronized Optional<String> reportParticipation(BFTValidatorSet validatorSet) {
    final var reason = notTakingPart(validatorSet);
    final var wasNotTakingPart = this.notTakingPart;
    this.notTakingPart = reason.isPresent();
    if (reason.isPresent() && !wasNotTakingPart) {
      return reason;
    }
    if (reason.isEmpty() && wasNotTakingPart) {
      return Optional.of(
          ("the configured validator %s is no longer registered with another key: it takes part"
                  + " in consensus whenever it is in the validator set")
              .formatted(this.format.apply(this.selfAddress.orElseThrow())));
    }
    return Optional.empty();
  }
}
