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
 * Test class for @EagerInit with self-reference.
 * Router depends on Interactor, which depends on MainScope (self-reference).
 */
class Router(val interactor: Interactor, val mainScope: MainScope) {
    fun route() {
        println("Router: routing with interactor")
    }
}
