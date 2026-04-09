package motif.sample.example;

import android.content.Context;
import motif.CachingStrategy;
import motif.Expose;
import motif.Scope;
import motif.sample.lib.db.Database;

@Scope(cachingStrategy = CachingStrategy.RUNTIME_SELECTABLE)
public interface ExampleScope {
    Database database();
    MultiSelector selector();  // Has @DoNotCache
    Listener listener();

    @motif.Objects
    abstract class Objects {
        @Expose
        Database database(Context context) {
            return new Database(context);
        }

        @Expose
        MultiSelector selector() {
            return new MultiSelector();
        }

        @Expose
        abstract Listener listener(ListenerImpl listenerImpl); // Wrapper pattern: ListenerImpl -> Listener

        @Expose
        ListenerImpl listenerImpl() {
            return new ListenerImpl();
        }
    }
}
