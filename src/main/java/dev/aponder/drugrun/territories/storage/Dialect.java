/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.storage;

import java.util.List;
import java.util.stream.Collectors;

/** SQL differences between the supported databases. */
public enum Dialect {
    SQLITE,
    MYSQL,
    /** Uses the MySQL driver shipped with Paper; MariaDB speaks the same protocol. */
    MARIADB;

    /** @return column definition for an auto-incrementing primary key */
    public String autoIncrementPrimaryKey() {
        return this == SQLITE ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
    }

    /** @return the keyword(s) that turn an INSERT into "insert unless the key already exists" */
    public String insertIgnore() {
        return this == SQLITE ? "INSERT OR IGNORE" : "INSERT IGNORE";
    }

    /**
     * Builds an upsert statement with one placeholder per column.
     *
     * @param table table name, including prefix
     * @param keyColumns primary-key columns
     * @param columns every column, key columns included, in placeholder order
     */
    public String upsert(String table, List<String> keyColumns, List<String> columns) {
        String cols = String.join(", ", columns);
        String marks = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        List<String> updates = columns.stream().filter(c -> !keyColumns.contains(c)).toList();
        if (this == SQLITE) {
            String set = updates.stream().map(c -> c + " = excluded." + c).collect(Collectors.joining(", "));
            return "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ") ON CONFLICT("
                    + String.join(", ", keyColumns) + ") DO UPDATE SET " + set;
        }
        String set = updates.stream().map(c -> c + " = VALUES(" + c + ")").collect(Collectors.joining(", "));
        return "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ") ON DUPLICATE KEY UPDATE " + set;
    }
}
