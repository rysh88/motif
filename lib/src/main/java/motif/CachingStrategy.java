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
     * Uses individual volatile fields with synchronized double-checked locking.
     * Cache fields are initialized with {@link motif.internal.None#NONE} sentinel value.
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Fast reads (~2ns per access)</li>
     *   <li>High class metadata overhead with many dependencies</li>
     *   <li>Lock contention during initialization can cause ANR with &gt;20 dependencies</li>
     *   <li>Default behavior for backward compatibility</li>
     * </ul>
     *
     * <p>Best for: Small scopes (&lt;20 cached dependencies)
     */
    VOLATILE_FIELDS,

    /**
     * Uses {@link java.util.concurrent.atomic.AtomicReferenceArray} with per-dependency locks and selective caching.
     * All cached dependencies are stored in a single array with separate lock object per dependency.
     * Intelligently skips caching for internal-only dependencies used once and optimizes thin wrapper methods.
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Slightly slower reads (~4ns per access, 2× slower than volatile fields)</li>
     *   <li>Minimal class metadata overhead (2 fields instead of N fields)</li>
     *   <li>True per-dependency lock granularity for maximum parallelism</li>
     *   <li>No hash collisions or CAS retry loops</li>
     *   <li>75% faster class loading</li>
     *   <li>No ANR risk regardless of dependency count</li>
     *   <li>Predictable performance characteristics</li>
     *   <li>Automatically detects and skips caching for internal-only single-use deps</li>
     *   <li>Automatically returns parameter directly when parameter type is assignable to return type</li>
     *   <li>Supports @DoNotCache annotation on classes to skip caching for specific types (checks class hierarchy)</li>
     *   <li>Reduces memory footprint by eliminating unnecessary cache fields</li>
     *   <li>Reduces lock overhead by avoiding synchronization for uncached deps</li>
     *   <li>Uses static classes for child dependencies for deduplication</li>
     * </ul>
     *
     * <p>Best for: Large scopes (&gt;20 cached dependencies) with mix of public and internal dependencies
     */
    SMART_CACHE,

    /**
     * Runtime-selectable mode generates both scope implementations (VOLATILE_FIELDS and SMART_CACHE).
     * At runtime, {@link MotifRuntimeConfig#cachingStrategy} determines which implementation is used.
     * Defaults to VOLATILE_FIELDS behavior if not configured.
     *
     * <p>VOLATILE_FIELDS variant (control):
     * <ul>
     *   <li>Traditional behavior without selective caching</li>
     *   <li>Caches all dependencies marked as cached</li>
     *   <li>@DoNotCache annotation on classes is ignored</li>
     * </ul>
     *
     * <p>SMART_CACHE variant (treatment):
     * <ul>
     *   <li>Includes selective caching optimization</li>
     *   <li>Automatically skips caching for single-use dependencies</li>
     *   <li>Automatically returns parameters directly for thin wrapper methods</li>
     *   <li>Respects @DoNotCache annotation on classes to skip caching for specific type hierarchies</li>
     * </ul>
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Generates multiple scope implementations at compile time</li>
     *   <li>Allows runtime switching between caching strategies</li>
     *   <li>Increased code size (2× scope implementation classes + wrapper)</li>
     *   <li>Enables A/B testing: control (VOLATILE_FIELDS) vs. treatment (SMART_CACHE with optimizations)</li>
     * </ul>
     *
     * <p>Best for: Applications that need server-driven caching strategy selection or A/B testing
     *
     * @see MotifRuntimeConfig
     */
    RUNTIME_SELECTABLE
}
