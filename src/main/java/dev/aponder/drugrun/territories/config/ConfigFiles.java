/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.config;

import java.io.IOException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Configuration-file access used by DrugRunTerritories.
 *
 * <p>This is the seam that DrugRunCore's Files API will fill once DrugRunCore is released.
 * Until then {@link LocalConfigFiles} provides the implementation. Callers depend only on this
 * interface, so swapping the implementation does not touch gameplay code.
 */
public interface ConfigFiles {

    /**
     * Loads a YAML file from the plugin data folder, creating it from the bundled default when
     * it does not exist. Keys present in the bundled default but missing from the file are added
     * (after a backup is written); keys the administrator changed are never overwritten.
     *
     * @param name file name relative to the data folder, for example {@code config.yml}
     * @return the loaded configuration
     * @throws IOException when the file cannot be read or is not valid YAML
     */
    default YamlConfiguration load(String name) throws IOException {
        return load(name, true);
    }

    /**
     * Same as {@link #load(String)}, but {@code mergeDefaults = false} only creates the file
     * when it is missing and never adds keys afterwards. Use it for data files such as territory
     * definitions, where re-adding a deleted default entry would be wrong.
     *
     * @param name file name relative to the data folder
     * @param mergeDefaults whether to add missing default keys
     * @return the loaded configuration
     * @throws IOException when the file cannot be read or is not valid YAML
     */
    YamlConfiguration load(String name, boolean mergeDefaults) throws IOException;

    /**
     * Writes a configuration atomically: the content goes to a temporary file which then
     * replaces the target, so a crash never leaves a half-written file.
     *
     * @param name file name relative to the data folder
     * @param configuration content to write
     * @throws IOException when the file cannot be written
     */
    void save(String name, YamlConfiguration configuration) throws IOException;
}
