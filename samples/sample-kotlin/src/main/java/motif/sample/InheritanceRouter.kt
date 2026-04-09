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

/**
 * Test class for @EagerInit with inheritance-based self-reference.
 * InheritanceRouter takes MainScopeParent (parent interface), but the scope being
 * processed is MainScope (which extends MainScopeParent).
 *
 * This reproduces the ActiveScope pattern where:
 * - ActiveRouter takes ActiveScope (parent interface)
 * - But we're processing ActiveScopeV2 (which extends ActiveScope)
 */
class InheritanceRouter(val scopeParent: MainScopeParent) {
    fun route() {
        println("InheritanceRouter: routing with parent scope interface")
        println("InheritanceRouter: greeter = ${scopeParent.greeter()}")
    }
}
