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
package testcases.KT010_smart_cache_selective_caching_kotlin

import motif.Creatable
import motif.DoNotCache
import motif.Expose

@motif.Scope(cachingStrategy = motif.CachingStrategy.SMART_CACHE)
interface Scope : Creatable<Scope.Dependencies> {

    // Rule 6: Multiple-use dependency - should be cached
    fun multiUseDep(): MultiUseDep

    // Rule 4: Public accessor - should be cached
    fun publicAccessorDep(): PublicAccessorDep

    // Rule 1: @DoNotCache annotation - should NOT be cached
    fun doNotCacheDep(): DoNotCacheDep

    // Rule 7: @Expose annotation - should be cached
    fun exposedDep(): ExposedDep

    // Rule 2: Type with @DoNotCache - should NOT be cached
    fun typeDoNotCacheDep(): DoNotCacheType

    // Helper to verify caching strategy
    fun getCachingStrategy(): motif.CachingStrategy

    // Consumer methods that use multiUseDep (to make it multi-use)
    fun consumer1(): Consumer1
    fun consumer2(): Consumer2

    @motif.Objects
    open class Objects {

        // Single-use internal dependency (only used by consumer1)
        fun singleUseDep(): SingleUseDep {
            return SingleUseDep()
        }

        // Multiple-use dependency (used by consumer1 and consumer2)
        fun multiUseDep(): MultiUseDep {
            return MultiUseDep()
        }

        // Dependency with public accessor
        fun publicAccessorDep(): PublicAccessorDep {
            return PublicAccessorDep()
        }

        // Dependency with @DoNotCache annotation
        @DoNotCache
        fun doNotCacheDep(): DoNotCacheDep {
            return DoNotCacheDep()
        }

        // Dependency with @Expose annotation
        @Expose
        fun exposedDep(): ExposedDep {
            return ExposedDep()
        }

        // Type with @DoNotCache annotation
        fun typeDoNotCacheDep(): DoNotCacheType {
            return DoNotCacheType()
        }

        // Consumers
        fun consumer1(singleUseDep: SingleUseDep, multiUseDep: MultiUseDep): Consumer1 {
            return Consumer1()
        }

        fun consumer2(multiUseDep: MultiUseDep): Consumer2 {
            return Consumer2()
        }

        fun getCachingStrategy(): motif.CachingStrategy {
            return motif.CachingStrategy.SMART_CACHE
        }
    }

    interface Dependencies
}
