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
package motif.sample

import android.view.ViewGroup
import android.widget.FrameLayout
import motif.CachingStrategy
import motif.Scope
import org.checkerframework.checker.guieffect.qual.UIEffect

// Generic router interface to test wildcard types
interface GenericRouter<T, U>

// Concrete implementation
class ConcreteRouter : GenericRouter<String, Int>

// Other classes to match the structure of DisableUberNavBikeCouriersAgendaCardScope
class ChildInteractor {
  class ChildPresenter
}
class ChildModalFactory

@Scope(cachingStrategy = CachingStrategy.RUNTIME_SELECTABLE)
interface ChildScope {
  fun router(): GenericRouter<*, *>

  @motif.Objects
  abstract class Objects {
    // Method 1: Abstract method returning concrete type (will be skipped - used once by cached dependent)
    internal abstract fun router(): ConcreteRouter

    // Method 2: Abstract method taking param and returning wildcard type (CACHED)
    // This matches router(router): ViewRouter<*, *> in DisableUberNavBikeCouriersAgendaCardScope
    internal abstract fun router(router: ConcreteRouter): GenericRouter<*, *>

    // Method 3: Abstract method returning concrete type (skipped)
    internal abstract fun interactor(): ChildInteractor

    // Method 4: Concrete method (skipped)
    internal fun modalFactory(): ChildModalFactory {
      return ChildModalFactory()
    }
  }
}
