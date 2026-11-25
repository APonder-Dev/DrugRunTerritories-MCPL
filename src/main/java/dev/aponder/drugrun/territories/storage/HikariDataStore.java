/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HikariCP-backed {@link DataStore} for SQLite, MySQL and MariaDB.
 *
 * <p>SQLite uses one connection and one worker thread, because SQLite allows a single writer;
 * this also keeps writes in submission order. MySQL/MariaDB use the configured pool size and
 * thread count.
 */
public final class HikariDataStore implements DataStore {

    private final StorageSettings settings;
    private final HikariDataSource dataSource;
    private final ThreadPoolExecutor executor;
    private final Logger logger;

    public HikariDataStore(StorageSettings settings, Path dataFolder, int threads, int queueSize, Logger logger) {
        this.settings = settings;
        this.logger = logger;
        boolean sqlite = settings.dialect() == Dialect.SQLITE;
        HikariConfig config = new HikariConfig();
        config.setPoolName("DrugRunTerritories");
        if (sqlite) {
            Path file = dataFolder.resolve(settings.sqliteFile()).normalize();
            config.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
            config.setDriverClassName("org.sqlite.JDBC");
            config.setMaximumPoolSize(1);
            config.setConnectionInitSql("PRAGMA busy_timeout = 5000");
        } else {
            config.setJdbcUrl("jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database()
                    + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8");
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setUsername(settings.username());
            config.setPassword(settings.password());
            config.setMaximumPoolSize(settings.poolSize());
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "100");
        }
        config.setConnectionTimeout(settings.connectionTimeout().toMillis());
        this.dataSource = new HikariDataSource(config);
        if (sqlite) {
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode = WAL");
                s.execute("PRAGMA foreign_keys = ON");
            } catch (SQLException e) {
                dataSource.close();
                throw new IllegalStateException("Could not open SQLite database: " + e.getMessage(), e);
            }
        }
        int workers = sqlite ? 1 : Math.max(1, threads);
        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(16, queueSize)),
                r -> {
                    Thread t = new Thread(r, "DrugRunTerritories-DB-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public Dialect dialect() {
        return settings.dialect();
    }

    @Override
    public int queryTimeoutSeconds() {
        return settings.queryTimeoutSeconds();
    }

    @Override
    public String table(String name) {
        return settings.tablePrefix() + name;
    }

    @Override
    public <T> CompletableFuture<T> query(SqlWork<T> work) {
        return submit(work, false);
    }

    @Override
    public <T> CompletableFuture<T> transaction(SqlWork<T> work) {
        return submit(work, true);
    }

    private <T> CompletableFuture<T> submit(SqlWork<T> work, boolean transactional) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    if (!transactional) {
                        future.complete(work.apply(connection));
                        return;
                    }
                    boolean previous = connection.getAutoCommit();
                    connection.setAutoCommit(false);
                    try {
                        T result = work.apply(connection);
                        connection.commit();
                        future.complete(result);
                    } catch (SQLException | RuntimeException e) {
                        rollbackQuietly(connection);
                        throw e;
                    } finally {
                        connection.setAutoCommit(previous);
                    }
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.severe("Database queue is full or closed; a database task was rejected.");
            future.completeExceptionally(e);
        }
        return future;
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Rollback failed", e);
        }
    }

    @Override
    public boolean healthy() {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(Math.max(1, settings.queryTimeoutSeconds()));
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Database tasks still running after 10 seconds; closing anyway.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        dataSource.close();
    }
}
