/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.druglab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import dev.aponder.druglab.api.DrugLabAPI;
import dev.aponder.druglab.api.event.FertilizerUseEvent;
import dev.aponder.druglab.api.event.PlantGrowEvent;
import dev.aponder.druglab.api.event.PlantHarvestEvent;
import dev.aponder.druglab.api.event.PlantPlaceEvent;
import dev.aponder.druglab.api.event.ShopTransactionEvent;
import dev.aponder.druglab.economy.ShopEntry;
import dev.aponder.druglab.plant.Fertilizer;
import dev.aponder.druglab.plant.GrowthStage;
import dev.aponder.druglab.plant.Plant;
import dev.aponder.druglab.plant.PlantType;
import dev.aponder.druglab.station.Station;
import dev.aponder.druglab.stats.Stat;
import dev.aponder.druglab.util.BlockKey;
import dev.aponder.druglab.util.Offset;
import dev.aponder.drugrun.territories.bonus.BonusType;
import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.progression.ProgressionRepository;
import dev.aponder.drugrun.territories.progression.ProgressionService;
import dev.aponder.drugrun.territories.storage.HikariDataStore;
import dev.aponder.drugrun.territories.support.FakeFactions;
import dev.aponder.drugrun.territories.support.Fixtures;
import dev.aponder.drugrun.territories.territory.TerritoryRegistry;
import dev.aponder.drugrun.territories.territory.TerritoryRepository;
import dev.aponder.drugrun.territories.territory.TerritoryService;
import dev.aponder.drugrun.territories.ui.Messages;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.mockito.MockedStatic;

/**
 * DrugLab integration through DrugLab's real event and plant classes (spec tests 36-50).
 * DrugLab's static API is mocked because the DrugLab plugin itself is not running.
 */
class DrugLabIntegrationTest {

    @TempDir
    Path dir;
    private ServerMock server;
    private WorldMock world;
    private HikariDataStore db;
    private MockedStatic<DrugLabAPI> api;
    private final FakeFactions factions = new FakeFactions();
    private Settings settings;
    private TerritoryRegistry registry;
    private ProgressionService progression;
    private TerritoryService territories;
    private PlantAuthorizationListener plants;
    private StationAccessListener stations;
    private DealerBonusListener dealer;
    private PlayerMock alphaMember;
    private PlayerMock bravoMember;
    private PlantType weed;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        db = Fixtures.sqlite(dir);
        settings = Fixtures.settings();
        registry = new TerritoryRegistry(List.of(Fixtures.territory("docks", 1000, 1000, Map.of(BonusType.DEALER_SELL, 1.10)),
                Fixtures.territory("greenhouse", 2000, 2000, Map.of(BonusType.CROP_GROWTH, 1.10, BonusType.PROCESSING_SPEED, 1.20))));
        factions.add("1", "Alpha");
        factions.add("2", "Bravo");
        factions.claim("world", 0, 0, factions.factions.get("1"));
        factions.claim("world", 1, 0, factions.factions.get("2"));
        alphaMember = server.addPlayer("alice");
        bravoMember = server.addPlayer("bob");
        factions.join(alphaMember.getUniqueId(), "1");
        factions.join(bravoMember.getUniqueId(), "2");

        progression = new ProgressionService(new ProgressionRepository(db), settings.progression(), 2, Fixtures.LOG);
        progression.load(Map.of(), List.of());
        territories = new TerritoryService(settings, registry, new TerritoryRepository(db), factions, progression,
                (def, now) -> Map.of(), Runnable::run, System::currentTimeMillis, () -> LocalTime.NOON, Fixtures.LOG);
        territories.initialize(Map.of());

        Messages messages = new Messages();
        messages.load(Fixtures.bundled("messages.yml"));
        plants = new PlantAuthorizationListener(factions, () -> settings, () -> registry, messages, progression, Fixtures.LOG, (p, l) -> true);
        stations = new StationAccessListener(factions, () -> settings, messages);
        dealer = new DealerBonusListener(factions, territories, () -> settings, Fixtures.LOG);

        // Three stages: 1 block, 2 blocks tall, then a block leaning one step east.
        weed = mock(PlantType.class);
        when(weed.id()).thenReturn("weed");
        when(weed.stages()).thenReturn(List.of(
                stage(new Offset(0, 0, 0)),
                stage(new Offset(0, 0, 0), new Offset(0, 1, 0)),
                stage(new Offset(0, 0, 0), new Offset(0, 1, 0), new Offset(1, 1, 0))));
        when(weed.finalStage()).thenReturn(2);

        api = mockStatic(DrugLabAPI.class);
        api.when(DrugLabAPI::isEnabled).thenReturn(true);
        api.when(() -> DrugLabAPI.getPlantType("weed")).thenReturn(weed);
        api.when(() -> DrugLabAPI.getMultiplier(any(UUID.class), any(Stat.class))).thenReturn(1.0);
    }

    @AfterEach
    void tearDown() {
        api.close();
        db.close();
        MockBukkit.unmock();
    }

    private static GrowthStage stage(Offset... offsets) {
        List<GrowthStage.StageBlock> blocks = new ArrayList<>();
        for (Offset o : offsets) {
            blocks.add(new GrowthStage.StageBlock(o, null));
        }
        return new GrowthStage(60_000, blocks);
    }

    private Block block(int x, int y, int z) {
        return world.getBlockAt(x, y, z);
    }

    private Plant plantAt(int x, int stage, UUID owner) {
        return new Plant(UUID.randomUUID(), "weed", BlockKey.of(block(x, 65, 3)), 0, owner, stage, 0D, System.currentTimeMillis());
    }

    // ---- planting -----------------------------------------------------------------------------

    @Test
    @DisplayName("36: a member may plant on their own claim")
    void authorizedPlanting() {
        PlantPlaceEvent event = new PlantPlaceEvent(alphaMember, weed, block(5, 65, 3), 0);
        plants.onPlace(event);
        assertFalse(event.isCancelled());
    }

    @Test
    @DisplayName("37: planting on another faction's claim is denied")
    void unauthorizedPlanting() {
        PlantPlaceEvent event = new PlantPlaceEvent(bravoMember, weed, block(5, 65, 3), 0);
        plants.onPlace(event);
        assertTrue(event.isCancelled());
    }

    @Test
    @DisplayName("38: a plant whose final stage would lean across the claim border is denied up front")
    void footprintCrossesBorder() {
        PlantPlaceEvent event = new PlantPlaceEvent(alphaMember, weed, block(15, 65, 3), 0);
        plants.onPlace(event);
        assertTrue(event.isCancelled(), "stage 3 would occupy x=16, which is Bravo's chunk");
    }

    @Test
    @DisplayName("Planting inside a territory arena is denied")
    void arenaPlanting() {
        PlantPlaceEvent event = new PlantPlaceEvent(alphaMember, weed, block(1010, 65, 1010), 0);
        plants.onPlace(event);
        assertTrue(event.isCancelled());
    }

    @Test
    @DisplayName("39: growth after a claim change freezes instead of entering the other claim")
    void growthAfterClaimChange() {
        Plant plant = plantAt(15, 1, alphaMember.getUniqueId());
        PlantGrowEvent grow = new PlantGrowEvent(plant, 2);
        plants.onGrow(grow);
        assertTrue(grow.isCancelled());
        factions.land.remove("world:1:0"); // Bravo unclaims: the neighbouring chunk is wilderness now
        PlantGrowEvent again = new PlantGrowEvent(plant, 2);
        plants.onGrow(again);
        assertFalse(again.isCancelled());
        PlantGrowEvent inside = new PlantGrowEvent(plantAt(5, 0, alphaMember.getUniqueId()), 1);
        plants.onGrow(inside);
        assertFalse(inside.isCancelled());
    }

    // ---- harvest & fertilizer -----------------------------------------------------------------

    @Test
    @DisplayName("40/41: harvesting needs break access to the plant's land")
    void harvesting() {
        Plant plant = plantAt(5, 2, alphaMember.getUniqueId());
        PlantHarvestEvent own = new PlantHarvestEvent(alphaMember, plant, true, new ArrayList<>(), 3);
        plants.onHarvest(own);
        assertFalse(own.isCancelled());
        PlantHarvestEvent enemy = new PlantHarvestEvent(bravoMember, plant, true, new ArrayList<>(), 3);
        plants.onHarvest(enemy);
        assertTrue(enemy.isCancelled());
    }

    @Test
    @DisplayName("42: fertilizing needs build access")
    void fertilizer() {
        Plant plant = plantAt(5, 1, alphaMember.getUniqueId());
        Fertilizer f = mock(Fertilizer.class);
        FertilizerUseEvent enemy = new FertilizerUseEvent(bravoMember, plant, f);
        plants.onFertilize(enemy);
        assertTrue(enemy.isCancelled());
        FertilizerUseEvent own = new FertilizerUseEvent(alphaMember, plant, f);
        plants.onFertilize(own);
        assertFalse(own.isCancelled());
    }

    @Test
    @DisplayName("Explosion harvests (no player) follow the configured drop mode without duplicating drops")
    void explosionDrops() {
        Plant plant = plantAt(5, 2, alphaMember.getUniqueId());
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Material.WHEAT, 10), new ItemStack(Material.WHEAT_SEEDS, 1)));
        PlantHarvestEvent normal = new PlantHarvestEvent(null, plant, true, drops, 4);
        plants.onHarvest(normal);
        assertEquals(2, normal.getDrops().size());

        org.bukkit.configuration.file.YamlConfiguration yaml = Fixtures.bundled("config.yml");
        yaml.set("raiding.plant-explosion-drops", "REDUCED");
        settings = dev.aponder.drugrun.territories.config.SettingsLoader.load(yaml).settings();
        PlantHarvestEvent reduced = new PlantHarvestEvent(null, plant,
                true, new ArrayList<>(List.of(new ItemStack(Material.WHEAT, 10), new ItemStack(Material.WHEAT_SEEDS, 1))), 4);
        plants.onHarvest(reduced);
        assertEquals(1, reduced.getDrops().size());
        assertEquals(5, reduced.getDrops().getFirst().getAmount());
        assertEquals(2, reduced.getExperience());

        yaml.set("raiding.plant-explosion-drops", "NONE");
        settings = dev.aponder.drugrun.territories.config.SettingsLoader.load(yaml).settings();
        PlantHarvestEvent none = new PlantHarvestEvent(null, plant, true, new ArrayList<>(List.of(new ItemStack(Material.WHEAT, 10))), 4);
        plants.onHarvest(none);
        assertTrue(none.getDrops().isEmpty());
        assertEquals(0, none.getExperience());
    }

    @Test
    @DisplayName("49: mature harvests by members count towards faction production")
    void productionStats() {
        Plant plant = plantAt(5, 2, alphaMember.getUniqueId());
        plants.onHarvestCompleted(new PlantHarvestEvent(alphaMember, plant, true, new ArrayList<>(), 0));
        plants.onHarvestCompleted(new PlantHarvestEvent(alphaMember, plant, false, new ArrayList<>(), 0));
        plants.onHarvestCompleted(new PlantHarvestEvent(null, plant, true, new ArrayList<>(), 0));
        assertEquals(1, progression.get("1").harvests());
    }

    // ---- stations -----------------------------------------------------------------------------

    @Test
    @DisplayName("34/35: stations open only for players with container access")
    void stationAccess() {
        Block stationBlock = block(5, 64, 5);
        api.when(() -> DrugLabAPI.getStation(stationBlock)).thenReturn(mock(Station.class));
        PlayerInteractEvent enemy = new PlayerInteractEvent(bravoMember, Action.RIGHT_CLICK_BLOCK, null, stationBlock, BlockFace.UP,
                EquipmentSlot.HAND);
        stations.onInteract(enemy);
        assertEquals(Event.Result.DENY, enemy.useInteractedBlock());
        PlayerInteractEvent own = new PlayerInteractEvent(alphaMember, Action.RIGHT_CLICK_BLOCK, null, stationBlock, BlockFace.UP,
                EquipmentSlot.HAND);
        stations.onInteract(own);
        assertNotEquals(Event.Result.DENY, own.useInteractedBlock());
        factions.available = false;
        PlayerInteractEvent unknown = new PlayerInteractEvent(alphaMember, Action.RIGHT_CLICK_BLOCK, null, stationBlock, BlockFace.UP,
                EquipmentSlot.HAND);
        stations.onInteract(unknown);
        assertEquals(Event.Result.DENY, unknown.useInteractedBlock(), "fails closed");
    }

    // ---- dealer bonus -------------------------------------------------------------------------

    private ShopTransactionEvent sale(PlayerMock player, double price, boolean canBuy, double buyPrice) {
        ShopEntry entry = mock(ShopEntry.class);
        when(entry.canBuy()).thenReturn(canBuy);
        when(entry.buyPrice()).thenReturn(buyPrice);
        when(entry.id()).thenReturn("weed");
        return new ShopTransactionEvent(player, ShopTransactionEvent.Type.SELL, entry, 10, price);
    }

    @Test
    @DisplayName("43/44/45: Docks owners get +10% on DrugLab's finished price (quality and demand already included)")
    void dealerBonus() {
        territories.forceCapture("docks", "1");
        ShopTransactionEvent member = sale(alphaMember, 77.7, false, 0);
        dealer.onTransaction(member);
        assertEquals(85.47, member.getPrice(), 1e-9);
        ShopTransactionEvent outsider = sale(bravoMember, 77.7, false, 0);
        dealer.onTransaction(outsider);
        assertEquals(77.7, outsider.getPrice(), 1e-9);
        ShopTransactionEvent buy = new ShopTransactionEvent(alphaMember, ShopTransactionEvent.Type.BUY, mock(ShopEntry.class), 1, 50);
        dealer.onTransaction(buy);
        assertEquals(50, buy.getPrice(), 1e-9);
    }

    @Test
    @DisplayName("Bonus never lifts a sale above the dealer's buy-back price (no buy/resell loop)")
    void buyBackCeiling() {
        territories.forceCapture("docks", "1");
        ShopTransactionEvent sale = sale(alphaMember, 95, true, 10); // dealer charges 10 x 10 = 100
        dealer.onTransaction(sale);
        assertEquals(100, sale.getPrice(), 1e-9);
    }

    @Test
    @DisplayName("46: cancelled transactions are never touched (and the listener never deposits money itself)")
    void cancelledTransaction() {
        territories.forceCapture("docks", "1");
        Plugin plugin = MockBukkit.createMockPlugin();
        server.getPluginManager().registerEvents(dealer, plugin);
        ShopTransactionEvent sale = sale(alphaMember, 100, false, 0);
        sale.setCancelled(true);
        server.getPluginManager().callEvent(sale);
        assertEquals(100, sale.getPrice(), 1e-9);
        ShopTransactionEvent live = sale(alphaMember, 100, false, 0);
        server.getPluginManager().callEvent(live);
        assertEquals(110, live.getPrice(), 1e-9);
    }

    @Test
    @DisplayName("No bonus when faction data is unavailable or the territory is lost")
    void bonusFailsClosed() {
        territories.forceCapture("docks", "1");
        factions.available = false;
        ShopTransactionEvent sale = sale(alphaMember, 100, false, 0);
        dealer.onTransaction(sale);
        assertEquals(100, sale.getPrice(), 1e-9);
        factions.available = true;
        territories.reset("docks");
        ShopTransactionEvent after = sale(alphaMember, 100, false, 0);
        dealer.onTransaction(after);
        assertEquals(100, after.getPrice(), 1e-9);
    }

    // ---- extended DrugLab hooks -------------------------------------------------------------

    @Test
    @DisplayName("The patched DrugLab exposes the extended hook API")
    void extendedApiDetected() {
        assertTrue(DrugLabIntegration.hasExtendedApi());
    }

    @Test
    @DisplayName("Greenhouse and Industrial bonuses reach DrugLab through ModifierProvider, for the owning faction only")
    void modifierProvider() {
        java.util.concurrent.atomic.AtomicBoolean mainThread = new java.util.concurrent.atomic.AtomicBoolean(true);
        TerritoryModifierProvider provider = new TerritoryModifierProvider(factions, territories, mainThread::get);
        Plant alphaPlant = plantAt(5, 0, alphaMember.getUniqueId());
        Plant bravoPlant = plantAt(5, 0, bravoMember.getUniqueId());
        assertEquals(1.0, provider.growthMultiplier(alphaPlant), 1e-9, "no territory, no bonus");
        territories.forceCapture("greenhouse", "1");
        assertEquals(1.10, provider.growthMultiplier(alphaPlant), 1e-9);
        assertEquals(1.0, provider.growthMultiplier(bravoPlant), 1e-9);
        assertEquals(1.0, provider.growthMultiplier(plantAt(5, 0, null)), 1e-9, "ownerless plants get nothing");
        Station station = mock(Station.class);
        when(station.owner()).thenReturn(alphaMember.getUniqueId());
        assertEquals(1.20, provider.processingMultiplier(station, null), 1e-9);
        mainThread.set(false);
        assertEquals(1.0, provider.growthMultiplier(alphaPlant), 1e-9, "off the main thread nothing is granted");
        mainThread.set(true);
        factions.available = false;
        assertEquals(1.0, provider.growthMultiplier(alphaPlant), 1e-9, "fails closed");
        factions.available = true;
        territories.reset("greenhouse");
        assertEquals(1.0, provider.growthMultiplier(alphaPlant), 1e-9, "bonus ends with ownership");
    }

    @Test
    @DisplayName("PlantDestroyEvent explosions follow the drop mode; plugin-caused destruction is untouched")
    void destroyEventDrops() {
        org.bukkit.configuration.file.YamlConfiguration yaml = Fixtures.bundled("config.yml");
        yaml.set("raiding.plant-explosion-drops", "NONE");
        settings = dev.aponder.drugrun.territories.config.SettingsLoader.load(yaml).settings();
        DrugLabHookListener hooks = new DrugLabHookListener(factions, progression, () -> settings);
        Plant plant = plantAt(5, 2, alphaMember.getUniqueId());
        dev.aponder.druglab.api.event.PlantDestroyEvent boom = new dev.aponder.druglab.api.event.PlantDestroyEvent(plant,
                dev.aponder.druglab.api.event.PlantDestroyEvent.Cause.EXPLOSION, true,
                new ArrayList<>(List.of(new ItemStack(Material.WHEAT, 8))), 5);
        hooks.onDestroy(boom);
        assertTrue(boom.getDrops().isEmpty());
        assertEquals(0, boom.getExperience());
        dev.aponder.druglab.api.event.PlantDestroyEvent api = new dev.aponder.druglab.api.event.PlantDestroyEvent(plant,
                dev.aponder.druglab.api.event.PlantDestroyEvent.Cause.PLUGIN, true,
                new ArrayList<>(List.of(new ItemStack(Material.WHEAT, 8))), 5);
        hooks.onDestroy(api);
        assertEquals(1, api.getDrops().size());
    }

    @Test
    @DisplayName("50: only paid SELL transactions count towards faction sales")
    void saleStatistics() {
        DrugLabHookListener hooks = new DrugLabHookListener(factions, progression, () -> settings);
        hooks.onSaleCompleted(new dev.aponder.druglab.api.event.ShopTransactionCompleteEvent(alphaMember, ShopTransactionEvent.Type.SELL,
                mock(ShopEntry.class), 12, 240.0, "good"));
        hooks.onSaleCompleted(new dev.aponder.druglab.api.event.ShopTransactionCompleteEvent(alphaMember, ShopTransactionEvent.Type.BUY,
                mock(ShopEntry.class), 5, 50.0, null));
        PlayerMock loner = server.addPlayer("loner");
        hooks.onSaleCompleted(new dev.aponder.druglab.api.event.ShopTransactionCompleteEvent(loner, ShopTransactionEvent.Type.SELL,
                mock(ShopEntry.class), 5, 50.0, null));
        assertEquals(12, progression.get("1").itemsSold());
        assertEquals(240.0, progression.get("1").salesValue(), 1e-9);
    }

    @Test
    @DisplayName("Collected processing jobs count for the collector's faction")
    void processingStatistics() {
        DrugLabHookListener hooks = new DrugLabHookListener(factions, progression, () -> settings);
        hooks.onProcessed(new dev.aponder.druglab.api.event.StationProcessCompleteEvent(mock(Station.class), null, null, bravoMember,
                new ItemStack(Material.SUGAR)));
        hooks.onProcessed(new dev.aponder.druglab.api.event.StationProcessCompleteEvent(mock(Station.class), null, null, null,
                new ItemStack(Material.SUGAR)));
        assertEquals(1, progression.get("2").jobsProcessed());
    }
}
