package liquibase.lockservice;

import liquibase.Scope;
import liquibase.change.Change;
import liquibase.GlobalConfiguration;
import liquibase.database.Database;
import liquibase.database.ObjectQuotingStrategy;
import liquibase.database.core.DB2Database;
import liquibase.database.core.DerbyDatabase;
import liquibase.database.core.MSSQLDatabase;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.changelog.ChangeGeneratorFactory;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import liquibase.exception.LockException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.executor.Executor;
import liquibase.executor.ExecutorService;
import liquibase.snapshot.InvalidExampleException;
import liquibase.snapshot.SnapshotGeneratorFactory;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.*;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Table;

import java.sql.SQLException;
import java.text.DateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static java.util.ResourceBundle.getBundle;

public class StandardLockService implements LockService {
    private static ResourceBundle coreBundle = getBundle("liquibase/i18n/liquibase-core");

    protected Database database;

    protected boolean hasChangeLogLock;

    private Long changeLogLockPollRate;
    private Long changeLogLockRecheckTime;

    private Boolean hasDatabaseChangeLogLockTable;
    private boolean isDatabaseChangeLogLockTableInitialized;
    private ObjectQuotingStrategy quotingStrategy;


    public StandardLockService() {
    }

    @Override
    public int getPriority() {
        return PRIORITY_DEFAULT;
    }

    @Override
    public boolean supports(Database database) {
        return true;
    }

    @Override
    public void setDatabase(Database database) {
        this.database = database;
    }

    public Long getChangeLogLockWaitTime() {
        if (changeLogLockPollRate != null) {
            return changeLogLockPollRate;
        }
        return GlobalConfiguration.CHANGELOGLOCK_WAIT_TIME.getCurrentValue();
    }

    @Override
    public void setChangeLogLockWaitTime(long changeLogLockWaitTime) {
        this.changeLogLockPollRate = changeLogLockWaitTime;
    }

    public Long getChangeLogLockRecheckTime() {
        if (changeLogLockRecheckTime != null) {
            return changeLogLockRecheckTime;
        }
        return GlobalConfiguration.CHANGELOGLOCK_POLL_RATE.getCurrentValue();
    }

    @Override
    public void setChangeLogLockRecheckTime(long changeLogLockRecheckTime) {
        this.changeLogLockRecheckTime = changeLogLockRecheckTime;
    }

    @Override
    public void init() throws DatabaseException {
        boolean createdTable = false;
        Executor executor = Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc",  database);

        if (!hasDatabaseChangeLogLockTable()) {
            try {
                executor.comment("Create Database Lock Table");
                executor.execute(new CreateDatabaseChangeLogLockTableStatement());
                database.commit();
                Scope.getCurrentScope().getLog(getClass()).fine(
                        "Created database lock table with name: " +
                                database.escapeTableName(
                                        database.getLiquibaseCatalogName(),
                                        database.getLiquibaseSchemaName(),
                                        database.getDatabaseChangeLogLockTableName()
                                )
                );
            } catch (DatabaseException e) {
                if (isLockTableExistsException(e)) {
                    //hit a race condition where the table got created by another node.
                    Scope.getCurrentScope().getLog(getClass()).fine("Database lock table already appears to exist " +
                            "due to exception: " + e.getMessage() + ". Continuing on");
                }  else {
                    throw e;
                }
            }
            this.hasDatabaseChangeLogLockTable = true;
            createdTable = true;
            hasDatabaseChangeLogLockTable = true;
        }

        if (!isDatabaseChangeLogLockTableInitialized(createdTable)) {
            executor.comment("Initialize Database Lock Table");
            executor.execute(new InitializeDatabaseChangeLogLockTableStatement());
            database.commit();
        }

        if (executor.updatesDatabase() && (database instanceof DerbyDatabase) && ((DerbyDatabase) database)
                .supportsBooleanDataType() || database.getClass().isAssignableFrom(DB2Database.class) && ((DB2Database) database)
    			.supportsBooleanDataType()) {
            //check if the changelog table is of an old smallint vs. boolean format
            String lockTable = database.escapeTableName(
                    database.getLiquibaseCatalogName(),
                    database.getLiquibaseSchemaName(),
                    database.getDatabaseChangeLogLockTableName()
            );
            Object obj = executor.queryForObject(
                    new RawSqlStatement(
                            "SELECT MIN(locked) AS test FROM " + lockTable + " FETCH FIRST ROW ONLY"
                    ), Object.class
            );
            if (!(obj instanceof Boolean)) { //wrong type, need to recreate table
                executor.execute(
                        new DropTableStatement(
                                database.getLiquibaseCatalogName(),
                                database.getLiquibaseSchemaName(),
                                database.getDatabaseChangeLogLockTableName(),
                                false
                        )
                );
                executor.execute(new CreateDatabaseChangeLogLockTableStatement());
                executor.execute(new InitializeDatabaseChangeLogLockTableStatement());
            }
        }

    }

    /**
     * @return true if the given exception is because the lock table already exists. Used for better logging and handling of race conditions.
     */
    protected boolean isLockTableExistsException(final DatabaseException e) {
        final Throwable cause = e.getCause();
        if (cause instanceof SQLException) {
            // now database specific error code evaluation can follow, currently for sqlserver, only
            if (database instanceof MSSQLDatabase) {
                // see https://docs.microsoft.com/en-us/sql/relational-databases/errors-events/database-engine-events-and-errors?view=sql-server-ver15
                return ((SQLException) cause).getErrorCode() == 2714;
            }
        }
        // fallback to default
        return e.getMessage() != null && e.getMessage().toLowerCase().contains("exists");
    }

    public boolean isDatabaseChangeLogLockTableInitialized(final boolean tableJustCreated) throws DatabaseException {
        if (!isDatabaseChangeLogLockTableInitialized) {
            Executor executor = Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc", database);

            try {
                isDatabaseChangeLogLockTableInitialized = executor.queryForInt(
                        new RawSqlStatement("SELECT COUNT(*) FROM " +
                                database.escapeTableName(
                                        database.getLiquibaseCatalogName(),
                                        database.getLiquibaseSchemaName(),
                                        database.getDatabaseChangeLogLockTableName()
                                )
                        )
                ) > 0;
            } catch (LiquibaseException e) {
                if (executor.updatesDatabase()) {
                    throw new UnexpectedLiquibaseException(e);
                } else {
                    //probably didn't actually create the table yet.
                    isDatabaseChangeLogLockTableInitialized = !tableJustCreated;
                }
            }
        }
        return isDatabaseChangeLogLockTableInitialized;
    }

    @Override
    public boolean hasChangeLogLock() {
        return hasChangeLogLock;
    }

    public boolean hasDatabaseChangeLogLockTable() throws DatabaseException {
        if (hasDatabaseChangeLogLockTable == null) {
            try {
                hasDatabaseChangeLogLockTable = SnapshotGeneratorFactory.getInstance()
                        .hasDatabaseChangeLogLockTable(database);
            } catch (LiquibaseException e) {
                throw new UnexpectedLiquibaseException(e);
            }
        }
        return hasDatabaseChangeLogLockTable;
    }


    @Override
    public void waitForLock() throws LockException {

        boolean locked = false;
        long timeToGiveUp = new Date().getTime() + (getChangeLogLockWaitTime() * 1000 * 60);
        while (!locked && (new Date().getTime() < timeToGiveUp)) {
            locked = acquireLock();
            if (!locked) {
                Scope.getCurrentScope().getLog(getClass()).info("Waiting for changelog lock....");
                try {
                    Thread.sleep(getChangeLogLockRecheckTime() * 1000);
                } catch (InterruptedException e) {
                    // Restore thread interrupt status
                    Thread.currentThread().interrupt();
                }
            }
        }

        if (!locked) {
            DatabaseChangeLogLock[] locks = listLocks();
            String lockedBy;
            if (locks.length > 0) {
                DatabaseChangeLogLock lock = locks[0];
                lockedBy = lock.getLockedBy() + " since " +
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(lock.getLockGranted());
            } else {
                lockedBy = "UNKNOWN";
            }
            throw new LockException("Could not acquire change log lock.  Currently locked by " + lockedBy);
        }
    }

    @Override
    public boolean acquireLock() throws LockException {
        if (hasChangeLogLock) {
            return true;
        }

        quotingStrategy = database.getObjectQuotingStrategy();

        Executor executor = Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc", database);

        try {
            database.rollback();
            this.init();

            Boolean locked = Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc", database).queryForObject(
                    new SelectFromDatabaseChangeLogLockStatement("LOCKED"), Boolean.class
            );

            if (locked) {
                return false;
            } else {

                executor.comment("Lock Database");
                int rowsUpdated = executor.update(new LockDatabaseChangeLogStatement());
                if ((rowsUpdated == -1) && (database instanceof MSSQLDatabase)) {
                    Scope.getCurrentScope().getLog(getClass()).fine(
                            "Database did not return a proper row count (Might have NOCOUNT enabled)"
                    );
                    database.rollback();
                    Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(
                            new LockDatabaseChangeLogStatement(), database
                    );
                    if (sql.length != 1) {
                        throw new UnexpectedLiquibaseException("Did not expect "+sql.length+" statements");
                    }
                    rowsUpdated = executor.update(new RawSqlStatement("EXEC sp_executesql N'SET NOCOUNT OFF " +
                            sql[0].toSql().replace("'", "''") + "'"));
                }
                if (rowsUpdated > 1) {
                    throw new LockException("Did not update change log lock correctly");
                }
                if (rowsUpdated == 0)
                {
                    // another node was faster
                    return false;
                }
                database.commit();
                Scope.getCurrentScope().getLog(getClass()).info(coreBundle.getString("successfully.acquired.change.log.lock"));

                hasChangeLogLock = true;

                database.setCanCacheLiquibaseTableInfo(true);
                return true;
            }
        } catch (Exception e) {
            throw new LockException(e);
        } finally {
            try {
                database.rollback();
            } catch (DatabaseException e) {
            }
        }

    }

    @Override
    public void releaseLock() throws LockException {

        ObjectQuotingStrategy incomingQuotingStrategy = null;
        if (this.quotingStrategy != null) {
            incomingQuotingStrategy = database.getObjectQuotingStrategy();
            database.setObjectQuotingStrategy(this.quotingStrategy);
        }

        Executor executor = Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc", database);
        try {
            if (this.hasDatabaseChangeLogLockTable()) {
                executor.comment("Release Database Lock");
                database.rollback();
                int updatedRows = executor.update(new UnlockDatabaseChangeLogStatement());
                if ((updatedRows == -1) && (database instanceof MSSQLDatabase)) {
                    Scope.getCurrentScope().getLog(getClass()).fine(
                            "Database did not return a proper row count (Might have NOCOUNT enabled.)"
                    );
                    database.rollback();
                    Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(
                            new UnlockDatabaseChangeLogStatement(), database
                    );
                    if (sql.length != 1) {
                        throw new UnexpectedLiquibaseException("Did not expect "+sql.length+" statements");
                    }
                    updatedRows = executor.update(
                            new RawSqlStatement(
                                    "EXEC sp_executesql N'SET NOCOUNT OFF " +
                                            sql[0].toSql().replace("'", "''") + "'"
                            )
                    );
                }
                if (updatedRows != 1) {
                    throw new LockException(
                            "Did not update change log lock correctly.\n\n" +
                                    updatedRows +
                                    " rows were updated instead of the expected 1 row using executor " +
                                    executor.getClass().getName() + "" +
                                    " there are " +
                                    executor.queryForInt(
                                            new RawSqlStatement(
                                                    "SELECT COUNT(*) FROM " +
                                                            database.getDatabaseChangeLogLockTableName()
                                            )
                                    ) +
                                    " rows in the table"
                    );
                }
                database.commit();
            }
        } catch (Exception e) {
            throw new LockException(e);
        } finally {
            try {
                hasChangeLogLock = false;

                database.setCanCacheLiquibaseTableInfo(false);
                Scope.getCurrentScope().getLog(getClass()).info("Successfully released change log lock");
                database.rollback();
            } catch (DatabaseException e) {
            }
            if (incomingQuotingStrategy != null) {
                database.setObjectQuotingStrategy(incomingQuotingStrategy);
            }
        }
    }

    @Override
    public DatabaseChangeLogLock[] listLocks() throws LockException {
        try {
            if (!this.hasDatabaseChangeLogLockTable()) {
                return new DatabaseChangeLogLock[0];
            }

            List<DatabaseChangeLogLock> allLocks = new ArrayList<>();
            SqlStatement sqlStatement = new SelectFromDatabaseChangeLogLockStatement(
                    "ID", "LOCKED", "LOCKGRANTED", "LOCKEDBY"
            );
            List<Map<String, ?>> rows = Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc", database).queryForList(sqlStatement);
            for (Map columnMap : rows) {
                Object lockedValue = columnMap.get("LOCKED");
                Boolean locked;
                if (lockedValue instanceof Number) {
                    locked = ((Number) lockedValue).intValue() == 1;
                } else {
                    locked = (Boolean) lockedValue;
                }
                if ((locked != null) && locked) {
                    Object lockGranted = columnMap.get("LOCKGRANTED");
                    final Date castedLockGranted;
                    if (lockGranted instanceof LocalDateTime) {
                        castedLockGranted = Date.from(((LocalDateTime) lockGranted).atZone(ZoneId.systemDefault()).toInstant());
                    } else {
                        castedLockGranted = (Date)lockGranted;
                    }
                    allLocks.add(
                            new DatabaseChangeLogLock(
                                    ((Number) columnMap.get("ID")).intValue(),
                                    castedLockGranted,
                                    (String) columnMap.get("LOCKEDBY")
                            )
                    );
                }
            }
            return allLocks.toArray(new DatabaseChangeLogLock[allLocks.size()]);
        } catch (Exception e) {
            throw new LockException(e);
        }
    }

    @Override
    public void forceReleaseLock() throws LockException, DatabaseException {
        this.init();
        releaseLock();
        /*try {
            releaseLock();
        } catch (LockException e) {
            // ignore ?
            Scope.getCurrentScope().getLog(getClass()).info("Ignored exception in forceReleaseLock: " + e.getMessage());
        }*/
    }

    @Override
    public void reset() {
        hasChangeLogLock = false;
        hasDatabaseChangeLogLockTable = null;
        isDatabaseChangeLogLockTableInitialized = false;
    }

    @Override
    public void destroy() throws DatabaseException {
        try {
            //
            // This code now uses the ChangeGeneratorFactory to
            // allow extension code to be called in order to
            // delete the changelog lock table.
            //
            // To implement the extension, you will need to override:
            // DropTableStatement
            // DropTableChange
            // DropTableGenerator
            //
            //
            DatabaseObject example =
                    new Table().setName(database.getDatabaseChangeLogLockTableName())
                               .setSchema(database.getLiquibaseCatalogName(), database.getLiquibaseSchemaName());
            if (SnapshotGeneratorFactory.getInstance().has(example, database)) {
                DatabaseObject table = SnapshotGeneratorFactory.getInstance().createSnapshot(example, database);
                DiffOutputControl diffOutputControl = new DiffOutputControl(true, true, false, null);
                Change[] change = ChangeGeneratorFactory.getInstance().fixUnexpected(table, diffOutputControl, database, database);
                SqlStatement[] sqlStatement = change[0].generateStatements(database);
                Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc", database).execute(sqlStatement[0]);
            }
            reset();
        } catch (InvalidExampleException e) {
            throw new UnexpectedLiquibaseException(e);
        }
    }
}
