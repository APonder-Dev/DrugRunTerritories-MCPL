/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.storage;

import java.time.Duration;

/**
 * Database connection settings ({@code storage} section).
 *
 * <p>{@link #toString()} never includes the password, so the record is safe to log.
 */
public record StorageSettings(
        Dialect dialect,
        String sqliteFile,
        String host,
        int port,
        String database,
        String username,
        String password,
        String tablePrefix,
        int poolSize,
        Duration connectionTimeout,
        int queryTimeoutSeconds) {

    @Override
    public String toString() {
        return "StorageSettings[dialect=" + dialect
                + (dialect == Dialect.SQLITE ? ", file=" + sqliteFile : ", host=" + host + ":" + port + "/" + database + ", user=" + username)
                + ", prefix=" + tablePrefix + ", pool=" + poolSize + "]";
    }
}
