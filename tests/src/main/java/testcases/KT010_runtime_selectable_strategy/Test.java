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
package testcases.KT010_runtime_selectable_strategy;

import static com.google.common.truth.Truth.assertThat;

import motif.CachingStrategy;
import motif.MotifRuntimeConfig;

public class Test {

    public static void run() {
        // Reset counter
        Counter.INSTANCE.setCounter(0);

        // Test default behavior (BASELINE)
        MotifRuntimeConfig.cachingStrategy = CachingStrategy.BASELINE;
        Scope baselineScope = new ScopeImpl();

        String first = baselineScope.cachedDependency();
        String second = baselineScope.cachedDependency();
        assertThat(first).isEqualTo("value_0");
        assertThat(second).isEqualTo("value_0");
        assertThat(first).isSameInstanceAs(second);

        // Reset counter
        Counter.INSTANCE.setCounter(100);

        // Test SMART_CACHE behavior
        MotifRuntimeConfig.cachingStrategy = CachingStrategy.SMART_CACHE;
        Scope smartCacheScope = new ScopeImpl();

        String firstSmart = smartCacheScope.cachedDependency();
        String secondSmart = smartCacheScope.cachedDependency();
        assertThat(firstSmart).isEqualTo("value_100");
        assertThat(secondSmart).isEqualTo("value_100");
        assertThat(firstSmart).isSameInstanceAs(secondSmart);

        // Reset to default
        MotifRuntimeConfig.cachingStrategy = CachingStrategy.BASELINE;
    }
}
