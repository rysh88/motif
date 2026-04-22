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
package testcases.T081_smart_cache_selective_caching;

import motif.Creatable;

@motif.Scope(cachingStrategy = motif.CachingStrategy.SMART_CACHE)
public interface Scope extends Creatable<Scope.Dependencies> {

    // Rule 6: Multiple-use dependency - should be cached
    MultiUseDep multiUseDep();

    // Rule 4: Public accessor - should be cached
    PublicAccessorDep publicAccessorDep();

    // Rule 1: @DoNotCache annotation - should NOT be cached
    DoNotCacheDep doNotCacheDep();

    // Rule 7: @Expose annotation - should be cached
    ExposedDep exposedDep();

    // Rule 2: Type with @DoNotCache - should NOT be cached
    DoNotCacheType typeDoNotCacheDep();

    // Helper to verify caching strategy
    motif.CachingStrategy getCachingStrategy();

    // Consumer methods that use multiUseDep (to make it multi-use)
    Consumer1 consumer1();
    Consumer2 consumer2();

    @motif.Objects
    class Objects {

        // Single-use internal dependency (only used by consumer1)
        SingleUseDep singleUseDep() {
            return new SingleUseDep();
        }

        // Multiple-use dependency (used by consumer1 and consumer2)
        MultiUseDep multiUseDep() {
            return new MultiUseDep();
        }

        // Dependency with public accessor
        PublicAccessorDep publicAccessorDep() {
            return new PublicAccessorDep();
        }

        // Dependency with @DoNotCache annotation
        @motif.DoNotCache
        DoNotCacheDep doNotCacheDep() {
            return new DoNotCacheDep();
        }

        // Dependency with @Expose annotation
        @motif.Expose
        ExposedDep exposedDep() {
            return new ExposedDep();
        }

        // Type with @DoNotCache annotation
        DoNotCacheType typeDoNotCacheDep() {
            return new DoNotCacheType();
        }

        // Consumers
        Consumer1 consumer1(SingleUseDep singleUseDep, MultiUseDep multiUseDep) {
            return new Consumer1();
        }

        Consumer2 consumer2(MultiUseDep multiUseDep) {
            return new Consumer2();
        }

        motif.CachingStrategy getCachingStrategy() {
            return motif.CachingStrategy.SMART_CACHE;
        }
    }

    interface Dependencies {}
}
