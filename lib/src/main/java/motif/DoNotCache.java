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
 * Annotation to prevent caching of dependencies in Motif DI framework.
 *
 * When applied to a factory method or type, prevents the dependency from being cached.
 * By default, applies to all caching strategies. Use onlyForSmartCacheMode = true
 * to apply only to SMART_CACHE mode while still caching in BASELINE mode.
 */
public @interface DoNotCache {
  /**
   * If true, only applies to SMART_CACHE mode. Dependencies will still be cached
   * when using BASELINE mode.
   *
   * Default: false (applies to all caching strategies)
   */
  boolean onlyForSmartCacheMode() default false;
}
