/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous database access used by DrugRunTerritories.
 *
 * <p>This is the seam that DrugRunCore's Databases API will fill once DrugRunCore is released.
 * Until then {@link HikariDataStore} implements it.
 *
 * <p>Rules for callers:
 * <ul>
 *   <li>Work runs on a bounded background pool, never on the server thread.</li>
 *   <li>The work function must not touch Bukkit world, entity or player state. Hop back to the
 *       main thread (see {@code MainThread}) before using the result for game changes.</li>
 *   <li>Never return a live {@code ResultSet} or {@code Connection}: copy data into plain objects.</li>
 * </ul>
 */
public interface DataStore extends AutoCloseable {

    /** SQL work that receives a pooled connection. */
    @FunctionalInterface
    interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** @return the database flavour, for dialect-specific SQL */
    Dialect dialect();

    /** @return seconds callers pass to {@code Statement.setQueryTimeout} */
    int queryTimeoutSeconds();

    /** @return {@code tablePrefix + name} */
    String table(String name);

    /** Runs {@code work} on the background pool with auto-commit on. */
    <T> CompletableFuture<T> query(SqlWork<T> work);

    /** Runs {@code work} in one transaction: committed on success, rolled back on any exception. */
    <T> CompletableFuture<T> transaction(SqlWork<T> work);

    /** @return whether a connection can be obtained and validated right now (blocking; background use only) */
    boolean healthy();

    /** Stops accepting work, waits a bounded time for queued work, then closes the pool. */
    @Override
    void close();
}
