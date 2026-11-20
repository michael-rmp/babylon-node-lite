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

use crate::jni_prelude::*;
use crate::lite::LiteCatchUpError;

/// Lite validator: catches up with the primary's database (see `StateManager::lite_catch_up()`).
/// Takes the number of consistency gate retries, the minimum time between two catch-ups and for
/// how long a catch-up may be postponed while the primary's files look unchanged (both in
/// milliseconds, see `StateManager::lite_follow()`), and returns the top state version of the
/// current view.
///
/// Panics (i.e. throws to Java) if the environment was not created by `initLite`.
#[no_mangle]
extern "system" fn Java_com_radixdlt_lite_RustLiteFollower_catchUp(
    env: JNIEnv,
    _class: JClass,
    j_node_rust_env: JObject,
    request_payload: jbyteArray,
) -> jbyteArray {
    jni_sbor_coded_call(
        &env,
        request_payload,
        |(gate_retries, min_interval_ms, max_postponement_ms): (u32, u32, u32)| -> Result<u64, LiteCatchUpError> {
            // Note: the environment's own lock is only held to clone these two handles.
            let (state_manager, follow_state) = {
                let node_rust_env = JNINodeRustEnvironment::get(&env, j_node_rust_env);
                let follow_state = node_rust_env
                    .lite_follow_state
                    .clone()
                    .expect("catchUp is only available in a lite validator environment");
                (node_rust_env.state_manager.clone(), follow_state)
            };
            // Catch-ups are serialized by this lock (in Java, they also run under the ledger lock):
            let mut follow_state = follow_state.lock().unwrap();
            state_manager
                .lite_follow(
                    &mut follow_state,
                    gate_retries,
                    std::time::Duration::from_millis(min_interval_ms as u64),
                    std::time::Duration::from_millis(max_postponement_ms as u64),
                )
                .map(|state_version| state_version.number())
        },
    )
}

/// Lite validator: returns the free memory of the C allocator to the system (see
/// `lite::lite_trim_memory()`). Returns whether any memory was released.
#[no_mangle]
extern "system" fn Java_com_radixdlt_lite_RustLiteFollower_trimMemory(
    env: JNIEnv,
    _class: JClass,
    _j_node_rust_env: JObject,
    request_payload: jbyteArray,
) -> jbyteArray {
    jni_sbor_coded_call(&env, request_payload, |_: ()| -> bool {
        crate::lite::lite_trim_memory()
    })
}

/// Lite validator: the ledger hashes which the current view shows right after the given state
/// version (`None` if the view does not reach it). Lets a lite validator check what its own
/// consensus committed against what the primary has written.
#[no_mangle]
extern "system" fn Java_com_radixdlt_lite_RustLiteFollower_ledgerHashesAt(
    env: JNIEnv,
    _class: JClass,
    j_node_rust_env: JObject,
    request_payload: jbyteArray,
) -> jbyteArray {
    jni_sbor_coded_call(
        &env,
        request_payload,
        |state_version_number: u64| -> Option<LedgerHashes> {
            let database = JNINodeRustEnvironment::get_database(&env, j_node_rust_env);
            let database = database.snapshot();
            database
                .get_committed_transaction_identifiers(StateVersion::of(state_version_number))
                .map(|identifiers| identifiers.resultant_ledger_hashes)
        },
    )
}

pub fn export_extern_functions() {}
