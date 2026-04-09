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

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import motif.CachingStrategy
import javax.inject.Named
import motif.Creatable
import motif.Expose
import motif.Scope
import java.util.Optional

@Scope(cachingStrategy = CachingStrategy.RUNTIME_SELECTABLE)
interface MainScope : MainScopeParent, Creatable<MainScope.Dependencies> {

  override fun greeter(): Greeter

  fun router(): Router

  fun interactor(): Interactor

  fun greeter(randomNumber: Int): ChildScope

  fun childScopeV2(randomNumber: Int, greeter: Greeter): ChildScope
  fun childScopeV3(): ChildScope

  fun randomNumber(): Int

  @motif.Objects
  abstract class Objects {

    @Named("name") fun name() = "World"

    @Expose
    fun greeter(@Named("name") name: String) = Greeter(name)

    @Expose
    fun randomNumber() = 11

    // Test case for wrapped child scope dependency in @EagerInit
    // Router depends on Interactor
    // Interactor depends on OptionalWrapper<ChildScope>
    // OptionalWrapper<ChildScope> is created by a factory that takes MainScope (self-reference)
    // This creates a circular dependency: Router -> Interactor -> OptionalWrapper<ChildScope> -> MainScope -> Router
    abstract fun router() : Router

    fun interactor(wrapper: OptionalWrapper<ChildScope>) = Interactor(wrapper)

    // Test case for inheritance-based scope self-reference with @EagerInit
    // This reproduces the ActiveScope problem:
    // - InheritanceRouter takes MainScopeParent (parent interface)
    // - But we're processing MainScope (which extends MainScopeParent)
    // - The old code would add MainScopeParent to eagerTypes, causing circular dependency
    // - The new code should detect that MainScopeParent is assignable from MainScope and skip it
    fun inheritanceRouter(scopeParent: MainScopeParent) = InheritanceRouter(scopeParent)

    // Factory method that provides MainScopeParent by returning the scope itself
    // This mirrors how ActiveScopeV2 might provide ActiveScope
    fun mainScopeParent(scope: MainScope): MainScopeParent = scope

    fun optionalChildScope(scope: MainScope): OptionalWrapper<ChildScope> {
      return OptionalWrapper(null)
    }
  }

  interface Dependencies
}
