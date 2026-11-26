/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.raid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aponder.drugrun.territories.api.RaidState;
import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.support.FakeFactions;
import dev.aponder.drugrun.territories.support.Fixtures;
import dev.aponder.drugrun.territories.territory.TerritoryRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** Explosion filtering and arena protection on MockBukkit (spec tests 26-35). */
class ProtectionListenerTest {

    private ServerMock server;
    private WorldMock world;
    private final FakeFactions factions = new FakeFactions();
    private TerritoryRegistry registry;
    private Settings settings;
    /** Blocks the fake DrugLab treats as plant/station blocks, mapped to their base. */
    private final Map<Block, Location> drugLabBlocks = new java.util.HashMap<>();
    private ExplosionListener explosions;
    private ArenaProtectionListener arena;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        settings = Fixtures.settings();
        // Arena covers x/z 1000..1031.
        registry = new TerritoryRegistry(List.of(Fixtures.territory("docks", 1000, 1000, Map.of())));
        factions.add("1", "Alpha");
        factions.add("2", "Bravo");
        factions.claim("world", 0, 0, factions.factions.get("1"));
        factions.claim("world", 1, 0, factions.factions.get("2"));
        explosions = new ExplosionListener(() -> registry, () -> settings, factions, () -> drugLabBlocks::get);
        arena = new ArenaProtectionListener(() -> registry, () -> settings);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Block block(int x, int y, int z) {
        return world.getBlockAt(x, y, z);
    }

    private List<Block> explode(Block... blocks) {
        List<Block> list = new ArrayList<>(List.of(blocks));
        explosions.filter(list);
        return list;
    }

    @Test
    @DisplayName("26/27: ordinary blocks in protected and raidable claims are left to FactionsUUID")
    void ordinaryClaimBlocks() {
        factions.raidStates.put("1", RaidState.PROTECTED);
        assertEquals(2, explode(block(1, 64, 1), block(2, 64, 2)).size());
        factions.raidStates.put("1", RaidState.VULNERABLE);
        assertEquals(2, explode(block(1, 64, 1), block(2, 64, 2)).size());
    }

    @Test
    @DisplayName("30: TNT never damages a territory arena")
    void arenaExplosion() {
        List<Block> left = explode(block(1005, 64, 1005), block(999, 64, 1005));
        assertEquals(List.of(block(999, 64, 1005)), left);
    }

    @Test
    @DisplayName("32/33/28: DrugLab crops are safe in protected or shielded claims and exposed in raidable ones")
    void drugLabCrops() {
        Block crop = block(3, 65, 3);
        drugLabBlocks.put(crop, crop.getLocation());
        factions.raidStates.put("1", RaidState.PROTECTED);
        assertTrue(explode(crop).isEmpty());
        factions.raidStates.put("1", RaidState.SHIELDED);
        assertTrue(explode(crop).isEmpty());
        factions.raidStates.put("1", RaidState.VULNERABLE);
        assertEquals(List.of(crop), explode(crop));
    }

    @Test
    @DisplayName("29: an explosion across a claim border treats each owner separately")
    void acrossBorder() {
        Block protectedCrop = block(15, 65, 3);
        Block raidableCrop = block(16, 65, 3);
        drugLabBlocks.put(protectedCrop, protectedCrop.getLocation());
        drugLabBlocks.put(raidableCrop, raidableCrop.getLocation());
        factions.raidStates.put("1", RaidState.PROTECTED);
        factions.raidStates.put("2", RaidState.VULNERABLE);
        assertEquals(List.of(raidableCrop), explode(protectedCrop, raidableCrop));
    }

    @Test
    @DisplayName("Multi-block plants follow the land of their base block")
    void plantUsesBase() {
        Block base = block(3, 65, 3);
        Block top = block(16, 66, 3); // leans into Bravo's raidable chunk
        drugLabBlocks.put(top, base.getLocation());
        factions.raidStates.put("1", RaidState.PROTECTED);
        factions.raidStates.put("2", RaidState.VULNERABLE);
        assertTrue(explode(top).isEmpty());
    }

    @Test
    @DisplayName("34/35: stations follow the same rules; faction lookups failing protects them")
    void stationsAndFailClosed() {
        Block station = block(4, 64, 4);
        drugLabBlocks.put(station, station.getLocation());
        factions.raidStates.put("1", RaidState.VULNERABLE);
        assertEquals(1, explode(station).size());
        factions.available = false;
        assertTrue(explode(station).isEmpty());
    }

    @Test
    @DisplayName("Players cannot break arena blocks without the bypass permission")
    void playerBreak() {
        PlayerMock player = server.addPlayer();
        Block inside = block(1010, 64, 1010);
        inside.setType(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(inside, player);
        arena.onBreak(event);
        assertTrue(event.isCancelled());
        player.addAttachment(MockBukkit.createMockPlugin(), ArenaProtectionListener.BYPASS_PERMISSION, true);
        BlockBreakEvent allowed = new BlockBreakEvent(inside, player);
        arena.onBreak(allowed);
        assertFalse(allowed.isCancelled());
        BlockBreakEvent outside = new BlockBreakEvent(block(10, 64, 10), server.addPlayer());
        arena.onBreak(outside);
        assertFalse(outside.isCancelled());
    }

    @Test
    @DisplayName("31: fluids from outside and pistons cannot change arena blocks")
    void indirect() {
        BlockFromToEvent flowIn = new BlockFromToEvent(block(999, 64, 1010), block(1000, 64, 1010));
        arena.onFlow(flowIn);
        assertTrue(flowIn.isCancelled());
        BlockFromToEvent flowInside = new BlockFromToEvent(block(1001, 64, 1010), block(1002, 64, 1010));
        arena.onFlow(flowInside);
        assertFalse(flowInside.isCancelled());
        BlockPistonExtendEvent farAway = new BlockPistonExtendEvent(block(997, 64, 1010), List.of(block(998, 64, 1010)), BlockFace.EAST);
        arena.onPistonExtend(farAway);
        assertFalse(farAway.isCancelled(), "the pushed block stops at x=999, outside the arena");
        BlockPistonExtendEvent intoArena = new BlockPistonExtendEvent(block(998, 64, 1010), List.of(block(999, 64, 1010)), BlockFace.EAST);
        arena.onPistonExtend(intoArena);
        assertTrue(intoArena.isCancelled(), "the pushed block would enter the arena at x=1000");
    }

    @Test
    @DisplayName("Arena protection can be switched off")
    void switchedOff() {
        org.bukkit.configuration.file.YamlConfiguration yaml = Fixtures.bundled("config.yml");
        yaml.set("territories.protection.prevent-arena-griefing", false);
        settings = dev.aponder.drugrun.territories.config.SettingsLoader.load(yaml).settings();
        assertEquals(1, explode(block(1005, 64, 1005)).size());
    }

    /** Collects log records for assertions. */
    private static final class Captured extends java.util.logging.Handler {
        final List<java.util.logging.LogRecord> records = new ArrayList<>();

        @Override
        public void publish(java.util.logging.LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static java.util.logging.Logger logger(Captured captured) {
        java.util.logging.Logger log = java.util.logging.Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(captured);
        return log;
    }

    @Test
    @DisplayName("A flow into an arena that another plugin re-allowed is reported once, then rate-limited")
    void leakWarning() {
        Captured captured = new Captured();
        ArenaProtectionListener listener = new ArenaProtectionListener(() -> registry, () -> settings, logger(captured));
        BlockFromToEvent reallowed = new BlockFromToEvent(block(999, 64, 1010), block(1000, 64, 1010));
        listener.onFlow(reallowed);
        assertTrue(reallowed.isCancelled());
        reallowed.setCancelled(false); // another plugin overrides
        listener.onFlowResult(reallowed);
        listener.onFlowResult(reallowed);
        assertEquals(1, captured.records.size());
        assertEquals(java.util.logging.Level.WARNING, captured.records.getFirst().getLevel());
        assertTrue(captured.records.getFirst().getMessage().contains("docks"));
        BlockFromToEvent inside = new BlockFromToEvent(block(1001, 64, 1010), block(1002, 64, 1010));
        BlockFromToEvent outside = new BlockFromToEvent(block(10, 64, 10), block(11, 64, 10));
        listener.onFlowResult(inside);
        listener.onFlowResult(outside);
        assertEquals(1, captured.records.size(), "flows inside an arena or far away are not warnings");
    }

    @Test
    @DisplayName("With general.debug on, flows touching an arena are traced with their decision")
    void debugTrace() {
        Captured captured = new Captured();
        org.bukkit.configuration.file.YamlConfiguration yaml = Fixtures.bundled("config.yml");
        yaml.set("general.debug", true);
        settings = dev.aponder.drugrun.territories.config.SettingsLoader.load(yaml).settings();
        ArenaProtectionListener listener = new ArenaProtectionListener(() -> registry, () -> settings, logger(captured));
        listener.onFlow(new BlockFromToEvent(block(999, 64, 1010), block(1000, 64, 1010)));
        listener.onFlow(new BlockFromToEvent(block(10, 64, 10), block(11, 64, 10)));
        assertEquals(1, captured.records.size(), "only arena-related flows are traced");
        assertTrue(captured.records.getFirst().getMessage().contains("cancelled, enters arena docks"));
    }
}
