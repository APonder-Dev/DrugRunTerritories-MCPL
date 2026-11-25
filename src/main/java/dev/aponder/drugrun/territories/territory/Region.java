/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import java.util.Objects;

/** Axis-aligned block box in one world. Bounds are inclusive. */
public record Region(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Region {
        Objects.requireNonNull(world, "world");
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Region min must not exceed max");
        }
    }

    /** Builds a region from two corners in any order. */
    public static Region of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Region(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** @return whether any column of chunk ({@code chunkX}, {@code chunkZ}) lies inside the region */
    public boolean intersectsChunk(int chunkX, int chunkZ) {
        int x0 = chunkX << 4;
        int z0 = chunkZ << 4;
        return x0 <= maxX && x0 + 15 >= minX && z0 <= maxZ && z0 + 15 >= minZ;
    }

    public int minChunkX() {
        return minX >> 4;
    }

    public int maxChunkX() {
        return maxX >> 4;
    }

    public int minChunkZ() {
        return minZ >> 4;
    }

    public int maxChunkZ() {
        return maxZ >> 4;
    }

    /** @return number of chunk columns the region touches */
    public long chunkCount() {
        return (long) (maxChunkX() - minChunkX() + 1) * (maxChunkZ() - minChunkZ() + 1);
    }
}
