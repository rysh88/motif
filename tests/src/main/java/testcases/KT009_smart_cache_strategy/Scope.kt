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
package testcases.KT009_smart_cache_strategy

import motif.CachingStrategy
import motif.Scope
import javax.inject.Named

@Scope(cachingStrategy = CachingStrategy.SMART_CACHE)
interface Scope {

    fun publicDependency(): String

    @Named("internal")
    fun internalDependency(): String

    @motif.Objects
    open class Objects {

        fun publicDependency(): String {
            return "public_${PublicCounter.counter++}"
        }

        @Named("internal")
        fun internalDependency(): String {
            return "internal_${InternalCounter.counter++}"
        }
    }
}

object PublicCounter {
    var counter = 0
}

object InternalCounter {
    var counter = 0
}
