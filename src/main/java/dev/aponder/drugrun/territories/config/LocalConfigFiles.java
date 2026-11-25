/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * File-system implementation of {@link ConfigFiles}, used until DrugRunCore's Files API exists.
 */
public final class LocalConfigFiles implements ConfigFiles {

    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dataFolder;
    private final Function<String, InputStream> bundledResources;
    private final Logger logger;

    /**
     * @param dataFolder plugin data folder
     * @param bundledResources opens a bundled default by name, or returns {@code null} when none exists
     * @param logger destination for backup and merge notices
     */
    public LocalConfigFiles(Path dataFolder, Function<String, InputStream> bundledResources, Logger logger) {
        this.dataFolder = dataFolder;
        this.bundledResources = bundledResources;
        this.logger = logger;
    }

    @Override
    public YamlConfiguration load(String name, boolean mergeDefaults) throws IOException {
        Path file = resolve(name);
        Files.createDirectories(file.getParent());
        YamlConfiguration defaults = readBundled(name);
        if (Files.notExists(file)) {
            if (defaults == null) {
                return new YamlConfiguration();
            }
            try (InputStream in = bundledResources.apply(name)) {
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                move(tmp, file);
            }
        }
        YamlConfiguration current = new YamlConfiguration();
        try {
            current.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException e) {
            throw new IOException(name + " is not valid YAML: " + e.getMessage(), e);
        }
        if (defaults != null && mergeDefaults) {
            List<String> added = mergeMissing(current, defaults);
            if (!added.isEmpty()) {
                backup(file);
                save(name, current);
                logger.info(name + ": added " + added.size() + " new default key(s): " + String.join(", ", added));
            }
        }
        return current;
    }

    @Override
    public void save(String name, YamlConfiguration configuration) throws IOException {
        Path file = resolve(name);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, configuration.saveToString(), StandardCharsets.UTF_8);
        move(tmp, file);
    }

    /**
     * Adds leaf keys present in {@code defaults} but absent from {@code target}. Existing values,
     * including ones that differ from the default, are left untouched. Map-valued sections that
     * the administrator emptied (for example {@code territory-overrides: {}}) are not refilled.
     *
     * @return the keys that were added
     */
    static List<String> mergeMissing(YamlConfiguration target, YamlConfiguration defaults) {
        List<String> added = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            if (!target.contains(key, true) && !parentIsExplicitScalar(target, key)) {
                target.set(key, defaults.get(key));
                target.setComments(key, defaults.getComments(key));
                added.add(key);
            }
        }
        return added;
    }

    private static boolean parentIsExplicitScalar(YamlConfiguration target, String key) {
        int dot = key.lastIndexOf('.');
        while (dot > 0) {
            String parent = key.substring(0, dot);
            if (target.contains(parent, true) && !target.isConfigurationSection(parent)) {
                return true;
            }
            dot = parent.lastIndexOf('.');
        }
        return false;
    }

    private YamlConfiguration readBundled(String name) throws IOException {
        InputStream in = bundledResources.apply(name);
        if (in == null) {
            return null;
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(reader);
            return yaml;
        } catch (InvalidConfigurationException e) {
            throw new IOException("Bundled " + name + " is invalid: " + e.getMessage(), e);
        }
    }

    private void backup(Path file) throws IOException {
        Path backups = dataFolder.resolve("backups");
        Files.createDirectories(backups);
        Path target = backups.resolve(file.getFileName() + "." + LocalDateTime.now().format(BACKUP_STAMP) + ".bak");
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path resolve(String name) throws IOException {
        Path file = dataFolder.resolve(name).normalize();
        if (!file.startsWith(dataFolder.normalize())) {
            throw new IOException("Refusing to access a file outside the data folder: " + name);
        }
        return file;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
