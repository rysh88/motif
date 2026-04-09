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
 * Annotation to indicate that a dependency should not be cached.
 *
 * <p>By default, this annotation applies to all caching strategies. However, you can
 * configure it to only apply to SMART_CACHE mode by setting {@link #onlyForSmartCacheMode()}
 * to true.
 *
 * <p>Example usage:
 * <pre>{@code
 * @motif.Scope
 * interface MyScope {
 *   @DoNotCache  // Applies to all modes
 *   MyDependency dependency();
 *
 *   @DoNotCache(onlyForSmartCacheMode = true)  // Only applies to SMART_CACHE
 *   MyOtherDependency otherDependency();
 * }
 * }</pre>
 */
public @interface DoNotCache {
    /**
     * If true, this annotation only applies to SMART_CACHE mode. Dependencies will still
     * be cached when using BASELINE mode.
     *
     * <p>If false (default), the dependency is not cached in any mode.
     *
     * @return true if DoNotCache should only apply to SMART_CACHE mode, false otherwise
     */
    boolean onlyForSmartCacheMode() default false;
}
