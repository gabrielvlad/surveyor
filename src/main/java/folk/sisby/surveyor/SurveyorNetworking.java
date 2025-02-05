package folk.sisby.surveyor;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import folk.sisby.surveyor.landmark.Landmark;
import folk.sisby.surveyor.landmark.LandmarkType;
import folk.sisby.surveyor.packet.C2SKnownLandmarksPacket;
import folk.sisby.surveyor.packet.C2SKnownStructuresPacket;
import folk.sisby.surveyor.packet.C2SKnownTerrainPacket;
import folk.sisby.surveyor.packet.C2SPacket;
import folk.sisby.surveyor.packet.SyncLandmarksAddedPacket;
import folk.sisby.surveyor.packet.SyncLandmarksRemovedPacket;
import folk.sisby.surveyor.packet.S2CStructuresAddedPacket;
import folk.sisby.surveyor.packet.S2CUpdateRegionPacket;
import folk.sisby.surveyor.structure.StructureStartSummary;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureType;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class SurveyorNetworking {

    public static Consumer<C2SPacket> C2S_SENDER = p -> {
    };

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(C2SKnownTerrainPacket.ID, (sv, p, h, b, se) -> handleServer(p, b, C2SKnownTerrainPacket::read, SurveyorNetworking::handleKnownTerrain));
        ServerPlayNetworking.registerGlobalReceiver(C2SKnownStructuresPacket.ID, (sv, p, h, b, se) -> handleServer(p, b, C2SKnownStructuresPacket::read, SurveyorNetworking::handleKnownStructures));
        ServerPlayNetworking.registerGlobalReceiver(C2SKnownLandmarksPacket.ID, (sv, p, h, b, se) -> handleServer(p, b, C2SKnownLandmarksPacket::read, SurveyorNetworking::handleKnownLandmarks));
        ServerPlayNetworking.registerGlobalReceiver(SyncLandmarksAddedPacket.ID, (sv, p, h, b, se) -> handleServer(p, b, SyncLandmarksAddedPacket::read, SurveyorNetworking::handleLandmarksAdded));
        ServerPlayNetworking.registerGlobalReceiver(SyncLandmarksRemovedPacket.ID, (sv, p, h, b, se) -> handleServer(p, b, SyncLandmarksRemovedPacket::read, SurveyorNetworking::handleLandmarksRemoved));
    }

    private static void handleKnownTerrain(ServerPlayerEntity player, ServerWorld world, WorldSummary summary, C2SKnownTerrainPacket packet) {
        Map<ChunkPos, BitSet> serverBits = summary.terrain().bitSet(SurveyorExploration.of(player));
        Map<ChunkPos, BitSet> clientBits = packet.regionBits();
        serverBits.forEach((rPos, set) -> {
            if (clientBits.containsKey(rPos)) set.andNot(clientBits.get(rPos));
            if (!set.isEmpty()) new S2CUpdateRegionPacket(rPos, summary.terrain().getRegion(rPos), set).send(player);
        });
    }

    private static void handleKnownStructures(ServerPlayerEntity player, ServerWorld world, WorldSummary summary, C2SKnownStructuresPacket packet) {
        Map<RegistryKey<Structure>, Map<ChunkPos, StructureStartSummary>> structures = summary.structures().asMap(SurveyorExploration.of(player));
        packet.structureKeys().forEach((key, pos) -> {
            if (structures.containsKey(key)) {
                structures.get(key).remove(pos);
                if (structures.get(key).isEmpty()) structures.remove(key);
            }
        });
        if (structures.isEmpty()) return;
        Map<RegistryKey<Structure>, RegistryKey<StructureType<?>>> structureTypes = new HashMap<>();
        Multimap<RegistryKey<Structure>, TagKey<Structure>> structureTags = HashMultimap.create();
        for (RegistryKey<Structure> key : structures.keySet()) {
            structureTypes.put(key, summary.structures().getType(key));
            structureTags.putAll(key, summary.structures().getTags(key));
        }
        new S2CStructuresAddedPacket(structures, structureTypes, structureTags).send(player);
    }

    private static void handleKnownLandmarks(ServerPlayerEntity player, ServerWorld world, WorldSummary summary, C2SKnownLandmarksPacket packet) {
        Map<LandmarkType<?>, Map<BlockPos, Landmark<?>>> landmarks = summary.landmarks().asMap(SurveyorExploration.of(player));
        packet.landmarks().forEach((type, pos) -> {
            if (landmarks.containsKey(type)) {
                landmarks.get(type).remove(pos);
                if (landmarks.get(type).isEmpty()) landmarks.remove(type);
            }
        });
        if (!landmarks.isEmpty()) new SyncLandmarksAddedPacket(landmarks).send(player);
    }

    private static void handleLandmarksAdded(ServerPlayerEntity player, ServerWorld world, WorldSummary summary, SyncLandmarksAddedPacket packet) {
        Multimap<LandmarkType<?>, BlockPos> changed = HashMultimap.create();
        packet.landmarks().forEach((type, map) -> map.forEach((pos, landmark) -> {
            if (player.getUuid().equals(landmark.owner())) summary.landmarks().putForBatch(changed, landmark);
        }));
        summary.landmarks().handleChanged(world, changed, false, player);
    }

    private static void handleLandmarksRemoved(ServerPlayerEntity player, ServerWorld world, WorldSummary summary, SyncLandmarksRemovedPacket packet) {
        Multimap<LandmarkType<?>, BlockPos> changed = HashMultimap.create();
        packet.landmarks().forEach((type, pos) -> {
            if (summary.landmarks().contains(type, pos) && player.getUuid().equals(summary.landmarks().get(type, pos).owner())) summary.landmarks().removeForBatch(changed, type, pos);
        });
        summary.landmarks().handleChanged(world, changed, false, player);
    }

    private static <T extends C2SPacket> void handleServer(ServerPlayerEntity player, PacketByteBuf buf, Function<PacketByteBuf, T> reader, ServerPacketHandler<T> handler) {
        T packet = reader.apply(buf);
        handler.handle(player, player.getServerWorld(), WorldSummary.of(player.getServerWorld()), packet);
    }

    public interface ServerPacketHandler<T extends C2SPacket> {
        void handle(ServerPlayerEntity player, ServerWorld world, WorldSummary summary, T packet);
    }
}
