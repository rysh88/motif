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

import motif.Creatable;

@motif.Scope(cachingStrategy = motif.CachingStrategy.SMART_CACHE)
public interface Scope extends Creatable<Scope.Dependencies> {

    // Consumer uses UsedDep
    Consumer consumer();

    // Note: unusedDep() is a factory method but it's NOT exposed via public accessor
    // and it's not used internally by any other dependency.
    // Rule 5: Dead code (usage count = 0) should NOT be cached

    @motif.Objects
    class Objects {

        // Dead code: not used by anyone, not exposed
        // SMART_CACHE should detect usage count = 0 and skip caching
        UnusedDep unusedDep() {
            return new UnusedDep();
        }

        // Used dependency: used by consumer
        // Should be cached (used internally)
        UsedDep usedDep() {
            return new UsedDep();
        }

        Consumer consumer(UsedDep usedDep) {
            return new Consumer(usedDep);
        }
    }

    interface Dependencies {}
}
