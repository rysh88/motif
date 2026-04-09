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
package testcases.KT010_runtime_selectable_strategy

import motif.CachingStrategy
import motif.Scope

@Scope(cachingStrategy = CachingStrategy.RUNTIME_SELECTABLE)
interface Scope {

    fun cachedDependency(): String

    @motif.Objects
    open class Objects {

        fun cachedDependency(): String {
            return "value_${Counter.counter++}"
        }
    }
}

object Counter {
    var counter = 0
}
