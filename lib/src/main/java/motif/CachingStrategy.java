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
 * Defines the caching strategy for scoped dependencies.
 */
public enum CachingStrategy {
    /**
     * Traditional strategy: volatile fields with synchronized double-checked locking.
     * Uses None.NONE sentinel. Caches all dependencies.
     */
    BASELINE,

    /**
     * Optimized strategy: AtomicReferenceArray with per-dependency locks.
     * Includes selective caching to skip single-use internal dependencies.
     */
    SMART_CACHE,

    /**
     * Generates both BASELINE and SMART_CACHE implementations.
     * Runtime selection via {@link MotifRuntimeConfig#cachingStrategy}.
     *
     * @see MotifRuntimeConfig
     */
    RUNTIME_SELECTABLE
}
