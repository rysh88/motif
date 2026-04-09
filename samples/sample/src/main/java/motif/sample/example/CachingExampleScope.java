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
package motif.sample.example;

import android.content.Context;
import motif.CachingStrategy;
import motif.Expose;
import motif.Scope;
import motif.sample.lib.db.Database;

@Scope(cachingStrategy = CachingStrategy.RUNTIME_SELECTABLE)
public interface CachingExampleScope {
    // Public accessor - used externally
    Database database();

    // Public accessor - used externally
    ExpensiveService service();

    @motif.Objects
    abstract class Objects {
        // Used multiple times: by database() and service()
        @Expose
        Context context() {
            return null; // Placeholder
        }

        // Heavy object used by multiple dependencies
        @Expose
        Database database(Context context) {
            return new Database(context);
        }

        // Used by service AND serviceImpl
        @Expose
        ExpensiveService service(Database database, ServiceImpl impl) {
            return new ExpensiveServiceWrapper(database, impl);
        }

        // Heavy computation, used multiple times internally
        @Expose
        ServiceImpl serviceImpl(Context context, Database database) {
            return new ServiceImpl(context, database);
        }
    }
}
