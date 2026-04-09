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
