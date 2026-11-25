/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable set of territory definitions with a chunk index, so "which territory is this block
 * in?" costs one hash lookup plus a bounds check against the few territories in that chunk.
 * A new registry is built whenever definitions change; readers keep whichever snapshot they hold.
 */
public final class TerritoryRegistry {

    /** Regions larger than this many chunks are rejected, to keep the index small. */
    public static final long MAX_CHUNKS_PER_TERRITORY = 16_384;

    private static final List<TerritoryDefinition> NONE = List.of();

    private final Map<String, TerritoryDefinition> byId;
    /** world -> chunk key -> enabled territories touching that chunk */
    private final Map<String, Map<Long, List<TerritoryDefinition>>> index;

    public TerritoryRegistry(Collection<TerritoryDefinition> definitions) {
        Map<String, TerritoryDefinition> ids = new LinkedHashMap<>();
        Map<String, Map<Long, List<TerritoryDefinition>>> idx = new HashMap<>();
        for (TerritoryDefinition def : definitions) {
            if (ids.putIfAbsent(def.id(), def) != null) {
                throw new IllegalArgumentException("Duplicate territory id " + def.id());
            }
            if (!def.enabled()) {
                continue;
            }
            Region r = def.region();
            if (r.chunkCount() > MAX_CHUNKS_PER_TERRITORY) {
                throw new IllegalArgumentException("Territory " + def.id() + " covers " + r.chunkCount()
                        + " chunks; the maximum is " + MAX_CHUNKS_PER_TERRITORY);
            }
            Map<Long, List<TerritoryDefinition>> world = idx.computeIfAbsent(r.world(), w -> new HashMap<>());
            for (int cx = r.minChunkX(); cx <= r.maxChunkX(); cx++) {
                for (int cz = r.minChunkZ(); cz <= r.maxChunkZ(); cz++) {
                    world.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>(1)).add(def);
                }
            }
        }
        this.byId = Map.copyOf(ids);
        Map<String, Map<Long, List<TerritoryDefinition>>> frozen = new HashMap<>();
        idx.forEach((world, chunks) -> {
            Map<Long, List<TerritoryDefinition>> copy = new HashMap<>(chunks.size() * 2);
            chunks.forEach((k, v) -> copy.put(k, List.copyOf(v)));
            frozen.put(world, copy);
        });
        this.index = frozen;
        this.ordered = List.copyOf(ids.values());
    }

    private final List<TerritoryDefinition> ordered;

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    /** @return every definition, enabled or not, in file order */
    public List<TerritoryDefinition> all() {
        return ordered;
    }

    /** @return enabled definitions only */
    public List<TerritoryDefinition> enabled() {
        return ordered.stream().filter(TerritoryDefinition::enabled).toList();
    }

    public Optional<TerritoryDefinition> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** @return the enabled territory whose region contains the block, if any */
    public Optional<TerritoryDefinition> at(String world, int x, int y, int z) {
        for (TerritoryDefinition def : inChunk(world, x >> 4, z >> 4)) {
            if (def.region().contains(world, x, y, z)) {
                return Optional.of(def);
            }
        }
        return Optional.empty();
    }

    /** @return enabled territories whose region touches the chunk */
    public List<TerritoryDefinition> inChunk(String world, int chunkX, int chunkZ) {
        Map<Long, List<TerritoryDefinition>> chunks = index.get(world);
        if (chunks == null) {
            return NONE;
        }
        return chunks.getOrDefault(chunkKey(chunkX, chunkZ), NONE);
    }

    /** @return whether any enabled territory touches the chunk */
    public boolean touchesChunk(String world, int chunkX, int chunkZ) {
        return !inChunk(world, chunkX, chunkZ).isEmpty();
    }

    /** @return the first other enabled territory whose region overlaps {@code def}'s region, if any */
    public Optional<TerritoryDefinition> overlapping(TerritoryDefinition def) {
        Region r = def.region();
        for (TerritoryDefinition other : enabled()) {
            if (other.id().equals(def.id()) || !other.world().equals(r.world())) {
                continue;
            }
            Region o = other.region();
            boolean overlap = r.minX() <= o.maxX() && r.maxX() >= o.minX() && r.minY() <= o.maxY() && r.maxY() >= o.minY()
                    && r.minZ() <= o.maxZ() && r.maxZ() >= o.minZ();
            if (overlap) {
                return Optional.of(other);
            }
        }
        return Optional.empty();
    }
}
