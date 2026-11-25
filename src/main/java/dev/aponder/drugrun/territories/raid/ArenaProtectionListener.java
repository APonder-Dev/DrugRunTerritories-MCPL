/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.raid;

import dev.aponder.drugrun.territories.config.Settings;
import dev.aponder.drugrun.territories.territory.TerritoryDefinition;
import dev.aponder.drugrun.territories.territory.TerritoryRegistry;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Keeps territory arenas intact against direct and indirect block changes: players, pistons,
 * fluids, fire, falling blocks and other entities, dispensers, bucket use and tree growth.
 * Every handler starts with a chunk-index lookup, which returns immediately for the vast majority
 * of blocks that are nowhere near a territory.
 *
 * <p>Also enforces the per-territory {@code pvp: DENY} rule.
 */
public final class ArenaProtectionListener implements Listener {

    /** Lets a player build and break inside arenas. */
    public static final String BYPASS_PERMISSION = "drugrun.territories.bypass.arena";

    private final Supplier<TerritoryRegistry> registry;
    private final Supplier<Settings> settings;

    public ArenaProtectionListener(Supplier<TerritoryRegistry> registry, Supplier<Settings> settings) {
        this.registry = registry;
        this.settings = settings;
    }

    // ---- players ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (arena(event.getBlock()).isPresent() && !event.getPlayer().hasPermission(BYPASS_PERMISSION)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (arena(event.getBlockPlaced()).isPresent() && !event.getPlayer().hasPermission(BYPASS_PERMISSION)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (arena(event.getBlock()).isPresent() && !event.getPlayer().hasPermission(BYPASS_PERMISSION)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (arena(event.getBlock()).isPresent() && !event.getPlayer().hasPermission(BYPASS_PERMISSION)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (arena(event.getEntity().getLocation().getBlock()).isEmpty()) {
            return;
        }
        if (event instanceof HangingBreakByEntityEvent byEntity && byEntity.getRemover() instanceof Player p
                && p.hasPermission(BYPASS_PERMISSION)) {
            return;
        }
        event.setCancelled(true);
    }

    // ---- indirect changes ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Optional<TerritoryDefinition> to = arena(event.getToBlock());
        if (to.isPresent() && !sameArena(to.get(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (pistonTouchesArena(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pistonTouchesArena(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (arena(event.getBlock()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (arena(event.getBlock()).isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        if (player != null && player.hasPermission(BYPASS_PERMISSION)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        Optional<TerritoryDefinition> to = arena(event.getBlock());
        if (to.isPresent() && !sameArena(to.get(), event.getSource())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (arena(event.getBlock()).isEmpty()) {
            return;
        }
        if (event.getEntity() instanceof Player p && p.hasPermission(BYPASS_PERMISSION)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        Block dispenser = event.getBlock();
        if (!(dispenser.getBlockData() instanceof Directional directional)) {
            return;
        }
        Block target = dispenser.getRelative(directional.getFacing());
        Optional<TerritoryDefinition> to = arena(target);
        if (to.isPresent() && !sameArena(to.get(), dispenser)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        Location origin = event.getLocation();
        Optional<TerritoryDefinition> source = arena(origin.getBlock());
        List<BlockState> blocks = event.getBlocks();
        blocks.removeIf(state -> {
            Optional<TerritoryDefinition> at = arena(state.getBlock());
            return at.isPresent() && (source.isEmpty() || !source.get().id().equals(at.get().id()));
        });
    }

    // ---- PvP rule -----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = attackingPlayer(event.getDamager());
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        Location l = victim.getLocation();
        registry.get().at(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ())
                .filter(def -> def.pvp() == TerritoryDefinition.PvpRule.DENY)
                .ifPresent(def -> event.setCancelled(true));
    }

    static Player attackingPlayer(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player p) {
                return p;
            }
        }
        return null;
    }

    // ---- helpers ------------------------------------------------------------------------------

    private boolean pistonTouchesArena(Block piston, List<Block> moved, BlockFace direction) {
        if (arena(piston.getRelative(direction)).isPresent()) {
            return true;
        }
        for (Block b : moved) {
            if (arena(b).isPresent() || arena(b.getRelative(direction)).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private boolean sameArena(TerritoryDefinition arena, Block other) {
        Optional<TerritoryDefinition> o = arena(other);
        return o.isPresent() && o.get().id().equals(arena.id());
    }

    /** @return the ARENA territory containing the block, when arena protection is on */
    private Optional<TerritoryDefinition> arena(Block block) {
        if (!settings.get().protection().preventArenaGriefing()) {
            return Optional.empty();
        }
        TerritoryRegistry reg = registry.get();
        String world = block.getWorld().getName();
        if (!reg.touchesChunk(world, block.getX() >> 4, block.getZ() >> 4)) {
            return Optional.empty();
        }
        return reg.at(world, block.getX(), block.getY(), block.getZ())
                .filter(def -> def.protection() == TerritoryDefinition.Protection.ARENA);
    }
}
