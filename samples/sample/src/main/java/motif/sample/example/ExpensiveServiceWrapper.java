package motif.sample.example;

import motif.sample.lib.db.Database;

public class ExpensiveServiceWrapper implements ExpensiveService {
    private final Database database;
    private final ServiceImpl impl;

    public ExpensiveServiceWrapper(Database database, ServiceImpl impl) {
        this.database = database;
        this.impl = impl;
    }

    @Override
    public void doWork() {
        // Implementation
    }
}
