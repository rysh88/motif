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
package motif;

/**
 * Runtime configuration for Motif dependency injection framework.
 */
public final class MotifRuntimeConfig {

    /**
     * Caching strategy for {@link CachingStrategy#RUNTIME_SELECTABLE} scopes.
     * Default: {@link CachingStrategy#BASELINE}.
     * Only affects newly created scopes.
     */
    public static CachingStrategy cachingStrategy = CachingStrategy.BASELINE;

    /**
     * Use per-dependency locks (true) or single lock (false).
     * Default: true (per-dependency locks for maximum parallelism).
     * Checked once at scope construction.
     */
    public static boolean usePerDependencyLock = true;

    private MotifRuntimeConfig() {
        throw new AssertionError("No instances.");
    }
}
