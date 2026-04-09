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
 *
 * <p>This class provides global configuration options that affect how Motif scopes
 * behave at runtime, particularly for scopes using {@link CachingStrategy#RUNTIME_SELECTABLE}.
 */
public final class MotifRuntimeConfig {

    /**
     * The caching strategy used at runtime for scopes configured with {@link CachingStrategy#RUNTIME_SELECTABLE}.
     *
     * <p>When a scope is annotated with RUNTIME_SELECTABLE, the compiler generates implementations for
     * all caching strategies (VOLATILE_FIELDS, SMART_CACHE).
     * This static variable controls which implementation is actually used at runtime.
     *
     * <p>Default value is {@link CachingStrategy#VOLATILE_FIELDS}.
     *
     * <p>This variable can be modified at runtime, but changes only affect newly created scopes.
     * Existing scope instances will continue using the strategy they were created with.
     *
     * <p>Example usage:
     * <pre>{@code
     * // Set strategy before creating scopes
     * MotifRuntimeConfig.cachingStrategy = CachingStrategy.SMART_CACHE;
     *
     * // Create scope - will use SMART_CACHE implementation
     * MyScope scope = new MyScopeImpl(dependencies);
     * }</pre>
     */
    public static CachingStrategy cachingStrategy = CachingStrategy.VOLATILE_FIELDS;

    /**
     * Controls whether scopes use per-dependency locks or a single global lock.
     *
     * <p>When true (default), each cached dependency has its own dedicated lock object, enabling
     * true parallel initialization with maximum lock granularity and no contention between unrelated dependencies.
     *
     * <p>When false, uses a single lock (synchronized on {@code this}) for all cached dependencies.
     * This reduces lock object overhead but may increase contention during scope initialization.
     *
     * <p>This setting affects scopes using {@link CachingStrategy#SMART_CACHE} and
     * {@link CachingStrategy#VOLATILE_FIELDS}. For RUNTIME_SELECTABLE scopes, it affects
     * both variant implementations.
     *
     * <p><b>Important:</b> This flag is checked once during scope construction and cannot be changed
     * after a scope is created. Lock fields are initialized based on this flag's value at construction time.
     *
     * <p>Default value is {@code true} (per-dependency locks for maximum parallelism).
     *
     * <p>Example usage:
     * <pre>{@code
     * // Use single lock to reduce memory overhead
     * MotifRuntimeConfig.usePerDependencyLock = false;
     *
     * // Create scope - will use single lock for all dependencies
     * MyScope scope = new MyScopeImpl(dependencies);
     *
     * // Changing the flag now has no effect on this scope
     * MotifRuntimeConfig.usePerDependencyLock = true;
     * }</pre>
     */
    public static boolean usePerDependencyLock = true;

    private MotifRuntimeConfig() {
        throw new AssertionError("No instances.");
    }
}
