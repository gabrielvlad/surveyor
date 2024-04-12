package folk.sisby.surveyor.terrain;

import folk.sisby.surveyor.Surveyor;
import folk.sisby.surveyor.SurveyorEvents;
import folk.sisby.surveyor.SurveyorExploration;
import folk.sisby.surveyor.WorldSummary;
import folk.sisby.surveyor.util.ChunkUtil;
import net.minecraft.block.Block;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.collection.IndexedIterable;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.WorldChunk;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WorldTerrainSummary {
    protected final RegistryKey<World> worldKey;
    protected final Map<ChunkPos, RegionSummary> regions = new ConcurrentHashMap<>();

    public WorldTerrainSummary(RegistryKey<World> worldKey, Map<ChunkPos, RegionSummary> regions) {
        this.worldKey = worldKey;
        this.regions.putAll(regions);
    }

    protected static ChunkPos regionPosOf(ChunkPos pos) {
        return new ChunkPos(pos.x >> RegionSummary.REGION_POWER, pos.z >> RegionSummary.REGION_POWER);
    }

    public boolean contains(ChunkPos pos) {
        ChunkPos regionPos = regionPosOf(pos);
        return regions.containsKey(regionPos) && regions.get(regionPos).contains(pos);
    }

    public ChunkSummary get(ChunkPos pos) {
        ChunkPos regionPos = regionPosOf(pos);
        return regions.get(regionPos).get(pos);
    }

    public RegionSummary getRegion(ChunkPos regionPos) {
        return regions.computeIfAbsent(regionPos, k -> new RegionSummary());
    }

    public IndexedIterable<Biome> getBiomePalette(ChunkPos pos) {
        ChunkPos regionPos = regionPosOf(pos);
        return regions.get(regionPos).getBiomePalette();
    }

    public IndexedIterable<Block> getBlockPalette(ChunkPos pos) {
        ChunkPos regionPos = regionPosOf(pos);
        return regions.get(regionPos).getBlockPalette();
    }

    public Map<ChunkPos, BitSet> bitSet(SurveyorExploration exploration) {
        Map<ChunkPos, BitSet> map = new HashMap<>();
        regions.forEach((p, r) -> map.put(p, r.bitSet()));
        return exploration == null ? map : exploration.limitTerrainBitset(worldKey, map);
    }

    public static Set<ChunkPos> toKeys(Map<ChunkPos, BitSet> bitSets) {
        return toKeys(bitSets, Comparator.comparingInt(pos -> pos.x + pos.z));
    }

    public static Set<ChunkPos> toKeys(Map<ChunkPos, BitSet> bitSets, ChunkPos originChunk) {
        ChunkPos oPos = new ChunkPos(RegionSummary.chunkToRegion(originChunk.x), RegionSummary.chunkToRegion(originChunk.z));
        return toKeys(bitSets, Comparator.comparingDouble(pos -> (oPos.x - pos.x) * (oPos.x - pos.x) + (oPos.z - pos.z) * (oPos.z - pos.z)));
    }

    public static Set<ChunkPos> toKeys(Map<ChunkPos, BitSet> bitSets, Comparator<ChunkPos> regionComparator) {
        Set<ChunkPos> set = new LinkedHashSet<>();
        bitSets.entrySet().stream().sorted(Map.Entry.comparingByKey(regionComparator)).forEach(e -> e.getValue().stream().forEach(i -> set.add(RegionSummary.chunkForBit(e.getKey(), i))));
        return set;
    }

    public void put(World world, WorldChunk chunk) {
        regions.computeIfAbsent(regionPosOf(chunk.getPos()), k -> new RegionSummary()).putChunk(world, chunk);
        SurveyorEvents.Invoke.terrainUpdated(world, chunk.getPos());
    }

    public int save(World world, File folder) {
        List<ChunkPos> savedRegions = new ArrayList<>();
        regions.forEach((pos, summary) -> {
            if (!summary.isDirty()) return;
            savedRegions.add(pos);
            NbtCompound regionCompound = summary.writeNbt(world.getRegistryManager(), new NbtCompound(), pos);
            File regionFile = new File(folder, "c.%d.%d.dat".formatted(pos.x, pos.z));
            try {
                NbtIo.writeCompressed(regionCompound, regionFile);
            } catch (IOException e) {
                Surveyor.LOGGER.error("[Surveyor] Error writing region summary file {}.", regionFile.getName(), e);
            }
        });
        return savedRegions.size();
    }

    public static WorldTerrainSummary load(World world, File folder) {
        File[] chunkFiles = folder.listFiles((file, name) -> {
            String[] split = name.split("\\.");
            if (split.length == 4 && split[0].equals("c") && split[3].equals("dat")) {
                try {
                    Integer.parseInt(split[1]);
                    Integer.parseInt(split[2]);
                    return true;
                } catch (NumberFormatException ignored) {
                }
            }
            return false;
        });
        Map<ChunkPos, RegionSummary> regions;
        regions = new HashMap<>();
        if (chunkFiles != null) {
            for (File regionFile : chunkFiles) {
                ChunkPos regionPos = new ChunkPos(Integer.parseInt(regionFile.getName().split("\\.")[1]), Integer.parseInt(regionFile.getName().split("\\.")[2]));
                NbtCompound regionCompound = null;
                try {
                    regionCompound = NbtIo.readCompressed(regionFile);
                } catch (IOException e) {
                    Surveyor.LOGGER.error("[Surveyor] Error loading region summary file {}.", regionFile.getName(), e);
                }
                if (regionCompound != null) regions.put(regionPos, new RegionSummary().readNbt(regionCompound, world.getRegistryManager()));
            }
        }
        return new WorldTerrainSummary(world.getRegistryKey(), regions);
    }

    public static void onChunkLoad(World world, WorldChunk chunk) {
        WorldSummary summary = WorldSummary.of(world);
        if ((!summary.terrain().contains(chunk.getPos()) || !ChunkUtil.airCount(chunk).equals(summary.terrain().get(chunk.getPos()).getAirCount()))){
            summary.terrain().put(world, chunk);
        }
    }

    public static void onChunkUnload(World world, WorldChunk chunk) {
        WorldSummary summary = WorldSummary.of(world);
        if (chunk.needsSaving()) {
            summary.terrain().put(world, chunk);
        }
    }
}
