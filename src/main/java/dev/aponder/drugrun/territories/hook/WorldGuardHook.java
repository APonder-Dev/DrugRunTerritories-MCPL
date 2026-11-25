/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.hook;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * WorldGuard build checks, used so DrugLab plant footprints respect WorldGuard regions as well as
 * FactionsUUID claims. Only loaded when WorldGuard is enabled. WorldGuard's own listeners protect
 * everything else; DrugRunTerritories never overrides them.
 */
public final class WorldGuardHook {

    /**
     * @return whether WorldGuard lets {@code player} build at {@code location}; players with
     *         WorldGuard bypass are allowed
     */
    public boolean canBuild(Player player, Location location) {
        LocalPlayer local = WorldGuardPlugin.inst().wrapPlayer(player);
        if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(local, BukkitAdapter.adapt(location.getWorld()))) {
            return true;
        }
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        return query.testBuild(BukkitAdapter.adapt(location), local);
    }
}
