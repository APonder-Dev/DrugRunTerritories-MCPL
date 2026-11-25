/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Structural guarantees from the specification. */
class ArchitectureTest {

    private static List<String> sources() throws IOException {
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).map(p -> root.relativize(p).toString().replace('\\', '/')).toList();
        }
    }

    @Test
    @DisplayName("58/59: no Loot Key implementation exists here, only the faction policy")
    void noLootKeyDuplication() throws IOException {
        List<String> lootKeyFiles = sources().stream().filter(s -> s.toLowerCase().contains("lootkey")).toList();
        assertEquals(List.of(
                "dev/aponder/drugrun/territories/api/LootKeyFactionPolicy.java",
                "dev/aponder/drugrun/territories/lootkey/FactionKillPolicy.java",
                "dev/aponder/drugrun/territories/lootkey/KillPolicySettings.java"), lootKeyFiles.stream().sorted().toList());
        for (String file : sources()) {
            String text = Files.readString(Path.of("src/main/java", file));
            assertTrue(!text.contains("loot_key") && !text.contains("redeem"), file + " must not store or redeem Loot Keys");
        }
    }

    @Test
    @DisplayName("No NMS, CraftBukkit internals or reflection hacks")
    void noInternals() throws IOException {
        for (String file : sources()) {
            String text = Files.readString(Path.of("src/main/java", file));
            assertTrue(!text.contains("net.minecraft.") && !text.contains("org.bukkit.craftbukkit") && !text.contains("setAccessible(")
                    && !text.contains("getDeclaredField("), file + " uses internals or reflection");
        }
    }

    @Test
    @DisplayName("DrugLab classes are only referenced inside the druglab package (plugin loads without DrugLab)")
    void drugLabIsolated() throws IOException {
        for (String file : sources()) {
            if (file.contains("/druglab/")) {
                continue;
            }
            String text = Files.readString(Path.of("src/main/java", file));
            assertTrue(!text.contains("import dev.aponder.druglab."), file + " imports DrugLab outside the integration package");
        }
    }

    @Test
    @DisplayName("No TODO or FIXME placeholders in production code")
    void noPlaceholders() throws IOException {
        for (String file : sources()) {
            String text = Files.readString(Path.of("src/main/java", file));
            assertTrue(!text.contains("TODO") && !text.contains("FIXME"), file + " contains a placeholder");
        }
    }
}
