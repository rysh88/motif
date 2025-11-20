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
     * Uses individual volatile fields with null initialization.
     * Cache fields start as null and use additional null checks for safety.
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Fast reads (~2ns per access)</li>
     *   <li>Slightly safer null handling than VOLATILE_FIELDS</li>
     *   <li>Lock contention during initialization can cause ANR with &gt;20 dependencies</li>
     * </ul>
     *
     * <p>Best for: Small scopes requiring strict null safety
     */
    VOLATILE_FIELDS_NULL_INIT,

    /**
     * Uses {@link java.util.concurrent.atomic.AtomicReferenceArray} for lock-free caching.
     * All cached dependencies are stored in a single array with atomic access semantics.
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Slightly slower reads (~5ns per access, 2.5× slower than volatile fields)</li>
     *   <li>Minimal class metadata overhead (1 field instead of N fields)</li>
     *   <li>Lock-free parallel initialization using CAS operations</li>
     *   <li>75% faster class loading</li>
     *   <li>No ANR risk regardless of dependency count</li>
     * </ul>
     *
     * <p>Best for: Large scopes (&gt;20 cached dependencies) or ANR-prone scenarios
     */
    ATOMIC_ARRAY
}
