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
package testcases.KT010_smart_cache_selective_caching_kotlin;

import static com.google.common.truth.Truth.assertThat;

import motif.CachingStrategy;

public class Test {

    public static void run() {
        Scope scope = new ScopeImpl();

        // Test Rule 6: Multiple-use dependency (should be cached)
        MultiUseDep multiUse1 = scope.multiUseDep();
        MultiUseDep multiUse2 = scope.multiUseDep();
        assertThat(multiUse1).isSameInstanceAs(multiUse2);

        // Test Rule 4: Dependency with public accessor (should be cached)
        PublicAccessorDep publicAccessor1 = scope.publicAccessorDep();
        PublicAccessorDep publicAccessor2 = scope.publicAccessorDep();
        assertThat(publicAccessor1).isSameInstanceAs(publicAccessor2);

        // Test Rule 1: Dependency with @DoNotCache (should NOT be cached)
        DoNotCacheDep doNotCache1 = scope.doNotCacheDep();
        DoNotCacheDep doNotCache2 = scope.doNotCacheDep();
        assertThat(doNotCache1).isNotSameInstanceAs(doNotCache2);

        // Test Rule 7: Dependency with @Expose (should be cached)
        ExposedDep exposed1 = scope.exposedDep();
        ExposedDep exposed2 = scope.exposedDep();
        assertThat(exposed1).isSameInstanceAs(exposed2);

        // Test Rule 2: Type with @DoNotCache annotation (should NOT be cached)
        DoNotCacheType typeDoNotCache1 = scope.typeDoNotCacheDep();
        DoNotCacheType typeDoNotCache2 = scope.typeDoNotCacheDep();
        assertThat(typeDoNotCache1).isNotSameInstanceAs(typeDoNotCache2);

        // Verify caching strategy is SMART_CACHE
        assertThat(scope.getCachingStrategy()).isEqualTo(CachingStrategy.SMART_CACHE);

        // Verify consumers work (testing multi-use scenario)
        Consumer1 consumer1Result1 = scope.consumer1();
        Consumer1 consumer1Result2 = scope.consumer1();
        assertThat(consumer1Result1).isSameInstanceAs(consumer1Result2);

        Consumer2 consumer2Result1 = scope.consumer2();
        Consumer2 consumer2Result2 = scope.consumer2();
        assertThat(consumer2Result1).isSameInstanceAs(consumer2Result2);
    }
}
