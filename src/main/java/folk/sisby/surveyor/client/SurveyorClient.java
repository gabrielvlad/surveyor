package folk.sisby.surveyor.client;

import com.google.common.collect.HashMultimap;
import folk.sisby.surveyor.Surveyor;
import folk.sisby.surveyor.SurveyorEvents;
import folk.sisby.surveyor.SurveyorExploration;
import folk.sisby.surveyor.WorldSummary;
import folk.sisby.surveyor.packet.C2SKnownLandmarksPacket;
import folk.sisby.surveyor.packet.C2SKnownStructuresPacket;
import folk.sisby.surveyor.packet.C2SKnownTerrainPacket;
import folk.sisby.surveyor.terrain.WorldTerrainSummary;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.Structure;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SurveyorClient implements ClientModInitializer {
    public static final String SERVERS_FILE_NAME = "servers.txt";

    public static File getSavePath(World world) {
        String saveFolder = String.valueOf(world.getBiomeAccess().seed);
        Path savePath = FabricLoader.getInstance().getGameDir().resolve(Surveyor.DATA_SUBFOLDER).resolve(Surveyor.ID).resolve(saveFolder);
        savePath.toFile().mkdirs();
        File serversFile = savePath.resolve(SERVERS_FILE_NAME).toFile();
        try {
            ServerInfo info = MinecraftClient.getInstance().getCurrentServerEntry();
            if (info != null && (!serversFile.exists() || !FileUtils.readFileToString(serversFile, StandardCharsets.UTF_8).contains(info.name + "\n" + info.address))) {
                FileUtils.writeStringToFile(serversFile, info.name + "\n" + info.address + "\n", StandardCharsets.UTF_8, true);
            }
        } catch (IOException e) {
            Surveyor.LOGGER.error("[Surveyor] Error writing servers file for save {}.", savePath, e);
        }
        return savePath.toFile();
    }

    public static File getWorldSavePath(World world) {
        String dimNamespace = world.getRegistryKey().getValue().getNamespace();
        String dimPath = world.getRegistryKey().getValue().getPath();
        return getSavePath(world).toPath().resolve(dimNamespace).resolve(dimPath).toFile();
    }

    public static boolean serverSupported() {
        return ClientPlayNetworking.canSend(C2SKnownTerrainPacket.ID);
    }

    @Nullable
    public static SurveyorExploration getExploration() {
        if (MinecraftClient.getInstance().isIntegratedServerRunning()) {
            ServerPlayerEntity serverPlayer = stealServerPlayer(getClientUuid());
            return serverPlayer == null ? null : SurveyorExploration.of(stealServerPlayer(getClientUuid()));
        } else {
            return ClientExploration.INSTANCE;
        }
    }

    public static UUID getClientUuid() {
        return Uuids.getUuidFromProfile(MinecraftClient.getInstance().getSession().getProfile());
    }

    public static ServerWorld stealServerWorld(RegistryKey<World> worldKey) {
        MinecraftServer integratedServer = MinecraftClient.getInstance().getServer();
        if (integratedServer == null) return null;
        return integratedServer.getWorld(worldKey);
    }

    public static ServerPlayerEntity stealServerPlayer(UUID uuid) {
        MinecraftServer integratedServer = MinecraftClient.getInstance().getServer();
        if (integratedServer == null) return null;
        return integratedServer.getPlayerManager().getPlayer(uuid);
    }

    @Override
    public void onInitializeClient() {
        SurveyorClientNetworking.init();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(ClientExploration::onLoad));
        ClientPlayConnectionEvents.DISCONNECT.register(((handler, client) -> ClientExploration.onUnload()));
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (WorldSummary.of(world).isClient()) {
                WorldTerrainSummary.onChunkLoad(world, chunk);
                ClientExploration.INSTANCE.addChunk(world.getRegistryKey(), chunk.getPos());
            }
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            if (WorldSummary.of(world).isClient()) WorldTerrainSummary.onChunkUnload(world, chunk);
        });
        ClientTickEvents.END_WORLD_TICK.register((world -> {
            if (!SurveyorClientEvents.INITIALIZING_WORLD) return;
            if (getExploration() != null && MinecraftClient.getInstance().player != null) {
                SurveyorClientEvents.INITIALIZING_WORLD = false;
                if (WorldSummary.of(world).isClient()) {
                    WorldSummary summary = WorldSummary.of(world);
                    new C2SKnownTerrainPacket(summary.terrain().bitSet(null)).send();
                    new C2SKnownStructuresPacket(summary.structures().keySet(null)).send();
                    new C2SKnownLandmarksPacket(summary.landmarks().keySet(null)).send();
                }
                SurveyorClientEvents.Invoke.worldLoad(MinecraftClient.getInstance().player.clientWorld, MinecraftClient.getInstance().player);
            }
        }));
        SurveyorEvents.Register.landmarksAdded(new Identifier(Surveyor.ID, "client"), ((world, worldLandmarks, landmarks) -> {
            SurveyorExploration exploration = getExploration();
            if (exploration != null) SurveyorClientEvents.Invoke.landmarksAdded(world, exploration.limitLandmarkKeySet(world.getRegistryKey(), worldLandmarks, HashMultimap.create(landmarks)));
        }));
        SurveyorEvents.Register.landmarksRemoved(new Identifier(Surveyor.ID, "client"), (world, summary, landmarks) -> SurveyorClientEvents.Invoke.landmarksRemoved(world, landmarks));
    }

    private record ClientExploration(Map<RegistryKey<World>, Map<ChunkPos, BitSet>> terrain, Map<RegistryKey<World>, Map<RegistryKey<Structure>, LongSet>> structures) implements SurveyorExploration {
        public static final String KEY_SHARED = "shared";
        public static final ClientExploration INSTANCE = new ClientExploration(new HashMap<>(), new HashMap<>());
        public static final ClientExploration SHARED = new ClientExploration(new HashMap<>(), new HashMap<>());
        public static File saveFile = null;

        public static void onLoad() {
            if (WorldSummary.of(MinecraftClient.getInstance().world).isClient()) {
                saveFile = getSavePath(MinecraftClient.getInstance().world).toPath().resolve(getClientUuid().toString() + ".dat").toFile();
                NbtCompound explorationNbt = new NbtCompound();
                if (saveFile.exists()) {
                    try {
                        explorationNbt = NbtIo.readCompressed(saveFile);
                    } catch (IOException e) {
                        Surveyor.LOGGER.error("[Surveyor] Error loading client exploration file.", e);
                    }
                }
                ClientExploration.INSTANCE.read(explorationNbt);
                ClientExploration.SHARED.read(explorationNbt.getCompound(KEY_SHARED));
            }
        }

        public static void onUnload() {
            if (saveFile != null) {
                try {
                    NbtCompound nbt = ClientExploration.INSTANCE.write(new NbtCompound());
                    NbtCompound sharedNbt = ClientExploration.SHARED.write(new NbtCompound());
                    nbt.put(KEY_SHARED, sharedNbt);
                    NbtIo.writeCompressed(nbt, saveFile);
                } catch (IOException e) {
                    Surveyor.LOGGER.error("[Surveyor] Error saving client exploration file.", e);
                }
                saveFile = null;
            }
            ClientExploration.INSTANCE.terrain().clear();
            ClientExploration.INSTANCE.structures.clear();
            ClientExploration.SHARED.terrain.clear();
            ClientExploration.SHARED.structures.clear();
        }

        @Override
        public Set<UUID> sharedPlayers() {
            return Set.of(Uuids.getUuidFromProfile(MinecraftClient.getInstance().getSession().getProfile()));
        }

        @Override
        public void addStructure(RegistryKey<World> worldKey, RegistryKey<Structure> structureKey, ChunkPos pos) {
            SurveyorExploration.super.addStructure(worldKey, structureKey, pos);
            updateClientForAddStructure(MinecraftClient.getInstance().world, structureKey, pos);
        }

        @Override
        public void mergeRegion(RegistryKey<World> worldKey, ChunkPos regionPos, BitSet bitSet) {
            SurveyorExploration.super.mergeRegion(worldKey, regionPos, bitSet);
            updateClientForMergeRegion(MinecraftClient.getInstance().world, regionPos, bitSet);
        }

        @Override
        public void addChunk(RegistryKey<World> worldKey, ChunkPos pos) {
            SurveyorExploration.super.addChunk(worldKey, pos);
            updateClientForAddChunk(MinecraftClient.getInstance().world, pos);
        }
    }
}
