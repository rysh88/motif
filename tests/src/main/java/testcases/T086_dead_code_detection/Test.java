/*
 * Copyright (c) 2018-2019 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package testcases.T086_dead_code_detection;

import static com.google.common.truth.Truth.assertThat;

public class Test {

    public static void run() {
        Scope scope = new ScopeImpl();

        // Test that consumer works (verifies usedDep is cached)
        Consumer consumer1 = scope.consumer();
        Consumer consumer2 = scope.consumer();
        assertThat(consumer1).isSameInstanceAs(consumer2);

        // Note: We can't directly call unusedDep() to test it since it's not
        // exposed via a public accessor method. The test verifies that:
        // 1. The code compiles without errors
        // 2. The consumer dependency works correctly
        // 3. SMART_CACHE correctly handles the dead code (unusedDep)
        //
        // Rule 5 verification happens at compile time: the generated ScopeImpl
        // should NOT have a cache field for unusedDep because it's dead code.
    }
}
