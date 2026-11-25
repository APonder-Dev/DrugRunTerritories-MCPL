/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import dev.aponder.drugrun.territories.config.ConfigFiles;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Validated edits to {@code territories.yml}. A change is checked against every other definition
 * (unique ids, no overlapping regions, size limits) before anything is written, written
 * atomically, and only then applied to the running service.
 */
public final class TerritoryEditor {

    /** Name of the definitions file. */
    public static final String FILE = "territories.yml";

    private final ConfigFiles files;
    private final TerritoryService service;

    public TerritoryEditor(ConfigFiles files, TerritoryService service) {
        this.files = files;
        this.service = service;
    }

    /** Creates or replaces a definition. @return problems; empty on success */
    public List<String> save(TerritoryDefinition def) throws IOException {
        List<TerritoryDefinition> next = new ArrayList<>(service.registry().all());
        next.removeIf(d -> d.id().equals(def.id()));
        next.add(def);
        List<String> problems = validate(next, def);
        if (!problems.isEmpty()) {
            return problems;
        }
        YamlConfiguration yaml = files.load(FILE, false);
        TerritoryDefinitionCodec.write(yaml, def);
        files.save(FILE, yaml);
        service.replaceDefinitions(new TerritoryRegistry(next));
        return List.of();
    }

    /** Removes a definition and its stored state. @return whether it existed */
    public boolean delete(String id) throws IOException {
        if (service.registry().get(id).isEmpty()) {
            return false;
        }
        List<TerritoryDefinition> next = new ArrayList<>(service.registry().all());
        next.removeIf(d -> d.id().equals(id));
        YamlConfiguration yaml = files.load(FILE, false);
        yaml.set("territories." + id, null);
        files.save(FILE, yaml);
        service.replaceDefinitions(new TerritoryRegistry(next));
        service.deleteState(id);
        return true;
    }

    static List<String> validate(List<TerritoryDefinition> all, TerritoryDefinition changed) {
        List<String> problems = new ArrayList<>();
        if (!changed.captureInsideRegion()) {
            problems.add("The capture point must be inside the region.");
        }
        try {
            TerritoryRegistry registry = new TerritoryRegistry(all);
            if (changed.enabled()) {
                registry.overlapping(changed).ifPresent(o -> problems.add("The region overlaps territory " + o.id() + "."));
            }
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
        return problems;
    }
}
