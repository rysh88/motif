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
package motif.sample.example2;

import motif.CachingStrategy;
import motif.Expose;
import motif.Scope;

@Scope(cachingStrategy = CachingStrategy.RUNTIME_SELECTABLE)
public interface ExampleScope {

    // Public accessor - makes sharedManager used externally
    SharedManager sharedManager();

    ExampleChildScope exampleChildScope(Parameter parameter);

    @motif.Objects
    abstract class Objects {

        @Expose
        SharedManager selector() {
            return new SharedManager();
        }

        // Uses sharedManager internally
        @Expose
        DataStore dataStore(SharedManager sharedManager, ListenerImpl impl) {
            return new DataStore();
        }

        @Expose
        abstract Listener listener(ListenerImpl listenerImpl); // Wrapper: ListenerImpl -> Listener

        // Now used by both dataStore and listener
        @Expose
        ListenerImpl listenerImpl() {
            return new ListenerImpl();
        }
    }
}
