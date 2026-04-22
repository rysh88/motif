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
package testcases.T087_smart_cache_passthrough_and_noncached_dependent;

import motif.Creatable;

@motif.Scope(cachingStrategy = motif.CachingStrategy.SMART_CACHE)
public interface Scope extends Creatable<Scope.Dependencies> {

    // Rule 3: Passthrough method - should NOT be cached
    // This abstract method just casts Dog to Animal (no construction cost)
    Animal getAnimal(Dog dog);

    // Rule 8: SharedDep used by non-cached dependent - should be cached
    // SharedDep is used once by NonCachedConsumer (which has @DoNotCache)
    // According to Rule 8, SharedDep should be cached
    SharedDep sharedDep();

    // Non-cached consumer that uses SharedDep
    NonCachedConsumer nonCachedConsumer();

    @motif.Objects
    class Objects {
        // Concrete method that creates a Dog
        Dog dog() {
            return new Dog();
        }

        // SharedDep used by NonCachedConsumer
        SharedDep sharedDep() {
            return new SharedDep();
        }

        // Consumer with @DoNotCache (should NOT be cached)
        @motif.DoNotCache
        NonCachedConsumer nonCachedConsumer(SharedDep sharedDep) {
            return new NonCachedConsumer(sharedDep);
        }
    }

    interface Dependencies {}
}
