package folk.sisby.surveyor.terrain;

import folk.sisby.surveyor.util.PaletteUtil;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import net.minecraft.block.Block;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.IndexedIterable;
import net.minecraft.util.collection.Int2ObjectBiMap;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class RegionSummary {
    public static final int REGION_POWER = 5;
    public static final int REGION_SIZE = 1 << REGION_POWER;
    public static final int BITSET_SIZE = 1 << (REGION_POWER * 2);
    public static final String KEY_BIOMES = "biomes";
    public static final String KEY_BLOCKS = "blocks";
    public static final String KEY_BIOME_WATER = "biomeWater";
    public static final String KEY_BIOME_FOLIAGE = "biomeFoliage";
    public static final String KEY_BIOME_GRASS = "biomeGrass";
    public static final String KEY_BLOCK_COLORS = "blockColors";
    public static final String KEY_CHUNKS = "chunks";

    protected final Int2ObjectBiMap<Biome> biomePalette = Int2ObjectBiMap.create(255);
    protected final Int2ObjectBiMap<Integer> rawBiomePalette = Int2ObjectBiMap.create(255);
    protected final Int2ObjectBiMap<Block> blockPalette = Int2ObjectBiMap.create(255);
    protected final Int2ObjectBiMap<Integer> rawBlockPalette = Int2ObjectBiMap.create(255);
    protected ChunkSummary[][] chunks = new ChunkSummary[REGION_SIZE][REGION_SIZE];

    protected boolean dirty = false;

    public static <T, O> List<O> mapPalette(IndexedIterable<T> palette, Function<T, O> mapper) {
        List<O> list = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) {
            list.add(mapper.apply(palette.get(i)));
        }
        return list;
    }

    public static int regionToChunk(int xz) {
        return xz << REGION_POWER;
    }

    public static int chunkToRegion(int xz) {
        return xz >> REGION_POWER;
    }

    public static int regionRelative(int xz) {
        return xz & (RegionSummary.REGION_SIZE - 1);
    }

    public static int bitForXZ(int x, int z) {
        return (x << REGION_POWER) + z;
    }

    public static int bitForChunk(ChunkPos pos) {
        return bitForXZ(regionRelative(pos.x), regionRelative(pos.z));
    }

    public static int xForBit(int i) {
        return i >> REGION_POWER;
    }

    public static int zForBit(int i) {
        return i & (REGION_SIZE - 1);
    }

    public static ChunkPos chunkForBit(ChunkPos rPos, int i) {
        return new ChunkPos(regionToChunk(rPos.x) + xForBit(i), regionToChunk(rPos.z) + zForBit(i));
    }

    public boolean contains(ChunkPos pos) {
        return chunks[regionRelative(pos.x)][regionRelative(pos.z)] != null;
    }

    public ChunkSummary get(ChunkPos pos) {
        return chunks[regionRelative(pos.x)][regionRelative(pos.z)];
    }

    public BitSet bitSet() {
        BitSet bitSet = new BitSet(BITSET_SIZE);
        for (int x = 0; x < REGION_SIZE; x++) {
            for (int z = 0; z < REGION_SIZE; z++) {
                if (chunks[x][z] != null) bitSet.set(bitForXZ(x, z));
            }
        }
        return bitSet;
    }

    public void putChunk(World world, WorldChunk chunk) {
        chunks[regionRelative(chunk.getPos().x)][regionRelative(chunk.getPos().z)] = new ChunkSummary(world, chunk, DimensionSupport.getSummaryLayers(world), biomePalette, rawBiomePalette, blockPalette, rawBlockPalette, !(world instanceof ServerWorld));
        dirty = true;
    }

    public RegionSummary readNbt(NbtCompound nbt, DynamicRegistryManager manager) {
        Registry<Biome> biomeRegistry = manager.get(RegistryKeys.BIOME);
        Registry<Block> blockRegistry = manager.get(RegistryKeys.BLOCK);
        nbt.getList(KEY_BIOMES, NbtElement.STRING_TYPE).stream().map(e -> biomeRegistry.get(new Identifier(e.asString()))).forEach(b -> {
            biomePalette.add(b);
            rawBiomePalette.add(biomeRegistry.getRawId(b));
        });
        nbt.getList(KEY_BLOCKS, NbtElement.STRING_TYPE).stream().map(e -> blockRegistry.get(new Identifier(e.asString()))).forEach(b -> {
            blockPalette.add(b);
            rawBlockPalette.add(blockRegistry.getRawId(b));
        });
        NbtCompound chunksCompound = nbt.getCompound(KEY_CHUNKS);
        for (String posKey : chunksCompound.getKeys()) {
            int x = regionRelative(Integer.parseInt(posKey.split(",")[0]));
            int z = regionRelative(Integer.parseInt(posKey.split(",")[1]));
            chunks[x][z] = new ChunkSummary(chunksCompound.getCompound(posKey));
        }
        return this;
    }

    public NbtCompound writeNbt(DynamicRegistryManager manager, NbtCompound nbt, ChunkPos regionPos) {
        nbt.put(KEY_BIOMES, new NbtList(mapPalette(biomePalette, b -> NbtString.of(manager.get(RegistryKeys.BIOME).getId(b).toString())), NbtElement.STRING_TYPE));
        nbt.put(KEY_BLOCKS, new NbtList(mapPalette(blockPalette, b -> NbtString.of(manager.get(RegistryKeys.BLOCK).getId(b).toString())), NbtElement.STRING_TYPE));
        nbt.putIntArray(KEY_BIOME_WATER, mapPalette(biomePalette, Biome::getWaterColor));
        nbt.putIntArray(KEY_BIOME_FOLIAGE, mapPalette(biomePalette, Biome::getFoliageColor));
        nbt.putIntArray(KEY_BIOME_GRASS, mapPalette(biomePalette, b -> b.getGrassColorAt(0, 0)));
        nbt.putIntArray(KEY_BLOCK_COLORS, mapPalette(blockPalette, b -> b.getDefaultMapColor().color));
        NbtCompound chunksCompound = new NbtCompound();
        for (int x = 0; x < REGION_SIZE; x++) {
            for (int z = 0; z < REGION_SIZE; z++) {
                if (chunks[x][z] != null) chunksCompound.put("%s,%s".formatted((regionPos.x << REGION_POWER) + x, (regionPos.z << REGION_POWER) + z), chunks[x][z].writeNbt(new NbtCompound()));
            }
        }
        nbt.put(KEY_CHUNKS, chunksCompound);
        return nbt;
    }

    public BitSet readBuf(DynamicRegistryManager manager, PacketByteBuf buf) {
        Registry<Biome> biomeRegistry = manager.get(RegistryKeys.BIOME);
        int[] rawBiomes = buf.readList(PacketByteBuf::readVarInt).stream().mapToInt(i -> i).toArray();
        Map<Integer, Integer> biomeRemap = new Int2IntArrayMap();
        for (int i = 0; i < rawBiomes.length; i++) {
            biomeRemap.put(i, PaletteUtil.rawIdOrAdd(biomePalette, rawBiomePalette, rawBiomes[i], biomeRegistry));
        }
        Registry<Block> blockRegistry = manager.get(RegistryKeys.BLOCK);
        int[] rawBlocks = buf.readList(PacketByteBuf::readVarInt).stream().mapToInt(i -> i).toArray();
        Map<Integer, Integer> blockRemap = new Int2IntArrayMap();
        for (int i = 0; i < rawBlocks.length; i++) {
            blockRemap.put(i, PaletteUtil.rawIdOrAdd(blockPalette, rawBlockPalette, rawBlocks[i], blockRegistry));
        }
        BitSet set = buf.readBitSet();
        int[] indices = set.stream().toArray();
        ArrayList<ChunkSummary> summaries = buf.readCollection(ArrayList::new, ChunkSummary::new);
        for (int i = 0; i < summaries.size(); i++) {
            ChunkSummary summary = summaries.get(i);
            summary.remap(biomeRemap, blockRemap);
            this.chunks[xForBit(indices[i])][zForBit(indices[i])] = summary;
        }
        dirty = true;
        return set;
    }

    public PacketByteBuf writeBuf(PacketByteBuf buf, BitSet set) {
        buf.writeCollection(mapPalette(rawBiomePalette, i -> i), PacketByteBuf::writeVarInt);
        buf.writeCollection(mapPalette(rawBlockPalette, i -> i), PacketByteBuf::writeVarInt);
        buf.writeBitSet(set);
        buf.writeCollection(set.stream().mapToObj(i -> chunks[xForBit(i)][zForBit(i)]).toList(), (b, summary) -> summary.writeBuf(b));
        return buf;
    }

    public boolean isDirty() {
        return dirty;
    }

    public IndexedIterable<Biome> getBiomePalette() {
        return biomePalette;
    }

    public IndexedIterable<Block> getBlockPalette() {
        return blockPalette;
    }
}
