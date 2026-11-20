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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.radixdlt.consensus.bft.BFTValidator;
import com.radixdlt.consensus.bft.BFTValidatorId;
import com.radixdlt.consensus.bft.BFTValidatorSet;
import com.radixdlt.crypto.ECDSASecp256k1PublicKey;
import com.radixdlt.crypto.ECKeyPair;
import com.radixdlt.rev2.ComponentAddress;
import com.radixdlt.utils.UInt192;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.Test;

public final class LiteIdentityGuardTest {
  private final ECDSASecp256k1PublicKey primaryKey = ECKeyPair.generateNew().getPublicKey();
  private final ECDSASecp256k1PublicKey otherKey = ECKeyPair.generateNew().getPublicKey();
  private final ECDSASecp256k1PublicKey ownKey = ECKeyPair.generateNew().getPublicKey();
  private final BFTValidatorId primary =
      BFTValidatorId.withKeyAndFakeDeterministicAddress(primaryKey);
  private final BFTValidatorId other = BFTValidatorId.withKeyAndFakeDeterministicAddress(otherKey);
  private final BFTValidatorId own = BFTValidatorId.withKeyAndFakeDeterministicAddress(ownKey);
  private final ComponentAddress primaryAddress = primary.getValidatorAddress();

  private static BFTValidatorSet set(BFTValidatorId... ids) {
    return BFTValidatorSet.from(Stream.of(ids).map(id -> BFTValidator.from(id, UInt192.ONE)));
  }

  @Test
  public void a_fresh_key_without_validator_address_is_fine() {
    final var guard = new LiteIdentityGuard(ownKey, Optional.empty(), Optional.of(primaryAddress));
    assertEquals(Optional.empty(), guard.violation(set(primary, other)));
  }

  @Test
  public void an_own_registered_validator_is_fine() {
    final var guard =
        new LiteIdentityGuard(
            ownKey, Optional.of(own.getValidatorAddress()), Optional.of(primaryAddress));
    assertEquals(Optional.empty(), guard.violation(set(primary, other, own)));
  }

  @Test
  public void the_primarys_key_is_a_violation_even_without_validator_address() {
    // E.g. the primary's keystore was copied into the lite validator's directory.
    final var guard =
        new LiteIdentityGuard(primaryKey, Optional.empty(), Optional.of(primaryAddress));
    final var violation = guard.violation(set(primary, other));
    assertTrue(violation.orElseThrow().contains("the primary's validator"));
  }

  @Test
  public void the_primarys_key_is_a_violation_even_if_the_primary_address_is_not_configured() {
    final var guard = new LiteIdentityGuard(primaryKey, Optional.empty(), Optional.empty());
    assertTrue(guard.violation(set(primary, other)).orElseThrow().contains("another validator"));
  }

  @Test
  public void the_key_of_any_other_validator_is_a_violation() {
    final var guard =
        new LiteIdentityGuard(
            otherKey, Optional.of(own.getValidatorAddress()), Optional.of(primaryAddress));
    assertTrue(guard.violation(set(primary, other)).isPresent());
  }

  @Test
  public void the_primarys_address_as_own_address_is_a_violation() {
    final var guard =
        new LiteIdentityGuard(ownKey, Optional.of(primaryAddress), Optional.of(primaryAddress));
    assertTrue(guard.violation(set(other)).isPresent());
  }

  @Test
  public void the_violation_shows_the_address_in_the_given_format() {
    final var guard =
        new LiteIdentityGuard(
            primaryKey,
            Optional.empty(),
            Optional.of(primaryAddress),
            address -> "validator_formatted");
    assertTrue(guard.violation(set(primary)).orElseThrow().contains("(validator_formatted)"));
  }

  @Test
  public void the_own_address_registered_with_another_key_is_reported_but_no_violation() {
    // e.g. a wrong key, or the epoch before a key change on ledger takes effect
    final var ownAddressOtherKey = BFTValidatorId.create(own.getValidatorAddress(), otherKey);
    final var guard =
        new LiteIdentityGuard(
            ownKey, Optional.of(own.getValidatorAddress()), Optional.of(primaryAddress));
    final var validatorSet = set(primary, ownAddressOtherKey);
    assertEquals(Optional.empty(), guard.violation(validatorSet));
    final var reason = guard.notTakingPart(validatorSet);
    assertTrue(reason.isPresent());
    assertTrue(reason.get(), reason.get().contains("it only follows"));
    assertTrue(reason.get(), reason.get().contains(otherKey.toHex()));
    assertTrue(reason.get(), reason.get().contains(ownKey.toHex()));
  }

  @Test
  public void taking_part_is_not_reported() {
    final var guard =
        new LiteIdentityGuard(
            ownKey, Optional.of(own.getValidatorAddress()), Optional.of(primaryAddress));
    assertEquals(Optional.empty(), guard.notTakingPart(set(primary, own)));
    // Not (yet) in the set, or only following:
    assertEquals(Optional.empty(), guard.notTakingPart(set(primary, other)));
    final var follower =
        new LiteIdentityGuard(ownKey, Optional.empty(), Optional.of(primaryAddress));
    assertEquals(Optional.empty(), follower.notTakingPart(set(primary, other)));
  }

  @Test
  public void participation_is_reported_only_when_it_changes() {
    // An operator moves the validator to another machine (key change) and back again.
    final var elsewhere = BFTValidatorId.create(own.getValidatorAddress(), otherKey);
    final var guard =
        new LiteIdentityGuard(
            ownKey, Optional.of(own.getValidatorAddress()), Optional.of(primaryAddress));
    assertEquals(Optional.empty(), guard.reportParticipation(set(primary, own)));
    assertEquals(Optional.empty(), guard.reportParticipation(set(primary, own)));
    final var away = guard.reportParticipation(set(primary, elsewhere));
    assertTrue(away.isPresent());
    assertTrue(away.get(), away.get().contains("it only follows"));
    assertEquals(Optional.empty(), guard.reportParticipation(set(primary, elsewhere)));
    assertEquals(Optional.empty(), guard.reportParticipation(set(primary, elsewhere)));
    final var back = guard.reportParticipation(set(primary, own));
    assertTrue(back.isPresent());
    assertTrue(back.get(), back.get().contains("no longer registered with another key"));
    assertEquals(Optional.empty(), guard.reportParticipation(set(primary, own)));
    // Nothing of it is a violation:
    assertEquals(Optional.empty(), guard.violation(set(primary, elsewhere)));
  }

  @Test
  public void not_taking_part_at_the_start_is_reported_once() {
    final var elsewhere = BFTValidatorId.create(own.getValidatorAddress(), otherKey);
    final var guard =
        new LiteIdentityGuard(
            ownKey, Optional.of(own.getValidatorAddress()), Optional.of(primaryAddress));
    assertTrue(guard.reportParticipation(set(primary, elsewhere)).isPresent());
    assertEquals(Optional.empty(), guard.reportParticipation(set(primary, elsewhere)));
  }
}
