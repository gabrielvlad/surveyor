package folk.sisby.surveyor.landmark;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import folk.sisby.surveyor.Surveyor;
import folk.sisby.surveyor.SurveyorEvents;
import folk.sisby.surveyor.SurveyorExploration;
import folk.sisby.surveyor.packet.SyncLandmarksAddedPacket;
import folk.sisby.surveyor.packet.SyncLandmarksRemovedPacket;
import folk.sisby.surveyor.util.MapUtil;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorldLandmarks {
    protected final RegistryKey<World> worldKey;
    protected final Map<LandmarkType<?>, Map<BlockPos, Landmark<?>>> landmarks = new ConcurrentHashMap<>();
    protected boolean dirty = false;

    public WorldLandmarks(RegistryKey<World> worldKey, Map<LandmarkType<?>, Map<BlockPos, Landmark<?>>> landmarks) {
        this.worldKey = worldKey;
        this.landmarks.putAll(landmarks);
    }

    public boolean contains(LandmarkType<?> type, BlockPos pos) {
        return landmarks.containsKey(type) && landmarks.get(type).containsKey(pos);
    }

    @SuppressWarnings("unchecked")
    public <T extends Landmark<T>> Landmark<T> get(LandmarkType<T> type, BlockPos pos) {
        return (Landmark<T>) landmarks.get(type).get(pos);
    }

    @SuppressWarnings("unchecked")
    public <T extends Landmark<T>> Map<BlockPos, T> asMap(LandmarkType<T> type, SurveyorExploration exploration) {
        Map<BlockPos, T> outMap = new HashMap<>();
        if (landmarks.containsKey(type)) landmarks.get(type).forEach((pos, landmark) -> {
            if (exploration == null || exploration.exploredLandmark(worldKey, landmark)) outMap.put(pos, (T) landmark);
        });
        return outMap;
    }

    public Map<LandmarkType<?>, Map<BlockPos, Landmark<?>>> asMap(SurveyorExploration exploration) {
        Map<LandmarkType<?>, Map<BlockPos, Landmark<?>>> outmap = new HashMap<>();
        landmarks.forEach((type, map) -> map.forEach((pos, landmark) -> {
            if (exploration == null || exploration.exploredLandmark(worldKey, landmark)) outmap.computeIfAbsent(type, t -> new HashMap<>()).put(pos, landmark);
        }));
        return outmap;
    }

    public Multimap<LandmarkType<?>, BlockPos> keySet(SurveyorExploration exploration) {
        Multimap<LandmarkType<?>, BlockPos> outMap = HashMultimap.create();
        landmarks.forEach((type, map) -> map.forEach((pos, landmark) -> {
            if (exploration == null || exploration.exploredLandmark(worldKey, landmark)) outMap.put(type, pos);
        }));
        return outMap;
    }

    public void handleChanged(World world, Multimap<LandmarkType<?>, BlockPos> changed, boolean local, @Nullable ServerPlayerEntity sender) {
        Map<LandmarkType<?>, Map<BlockPos, Landmark<?>>> landmarksAddedChanged = new HashMap<>();
        Multimap<LandmarkType<?>, BlockPos> landmarksRemoved = HashMultimap.create();
        changed.forEach((type, pos) -> {
            if (contains(type, pos)) {
                landmarksAddedChanged.computeIfAbsent(type, t -> new HashMap<>()).put(pos, get(type, pos));
            } else {
                landmarksRemoved.put(type, pos);
            }
        });
        if (!landmarksRemoved.isEmpty()) SurveyorEvents.Invoke.landmarksRemoved(world, landmarksRemoved);
        if (!landmarksAddedChanged.isEmpty()) SurveyorEvents.Invoke.landmarksAdded(world, MapUtil.keyMultiMap(landmarksAddedChanged));
        if (!local) {
            if (!landmarksRemoved.isEmpty()) new SyncLandmarksRemovedPacket(landmarksRemoved).send(sender, world);
            if (!landmarksAddedChanged.isEmpty()) new SyncLandmarksAddedPacket(landmarksAddedChanged).send(sender, world);
        }
    }

    public Multimap<LandmarkType<?>, BlockPos> putForBatch(Multimap<LandmarkType<?>, BlockPos> changed, Landmark<?> landmark) {
        landmarks.computeIfAbsent(landmark.type(), t -> new ConcurrentHashMap<>()).put(landmark.pos(), landmark);
        dirty = true;
        changed.put(landmark.type(), landmark.pos());
        return changed;
    }

    public void putLocal(World world, Landmark<?> landmark) {
        Multimap<LandmarkType<?>, BlockPos> changed = landmark.put(HashMultimap.create(), world, this);
        handleChanged(world, changed, true, null);
    }

    public void put(World world, Landmark<?> landmark) {
        Multimap<LandmarkType<?>, BlockPos> changed = landmark.put(HashMultimap.create(), world, this);
        handleChanged(world, changed, false, null);
    }

    public void put(ServerPlayerEntity sender, ServerWorld world, Landmark<?> landmark) {
        Multimap<LandmarkType<?>, BlockPos> changed = landmark.put(HashMultimap.create(), world, this);
        handleChanged(world, changed, false, sender);
    }

    public Multimap<LandmarkType<?>, BlockPos> removeForBatch(Multimap<LandmarkType<?>, BlockPos> changed, LandmarkType<?> type, BlockPos pos) {
        if (!landmarks.containsKey(type) || !landmarks.get(type).containsKey(pos)) return changed;
        landmarks.get(type).remove(pos);
        if (landmarks.get(type).isEmpty()) landmarks.remove(type);
        dirty = true;
        changed.put(type, pos);
        return changed;
    }

    public void removeLocal(World world, LandmarkType<?> type, BlockPos pos) {
        if (!landmarks.containsKey(type) || !landmarks.get(type).containsKey(pos)) return;
        Multimap<LandmarkType<?>, BlockPos> changed = landmarks.get(type).get(pos).remove(HashMultimap.create(), world, this);
        handleChanged(world, changed, true, null);
    }

    public void remove(World world, LandmarkType<?> type, BlockPos pos) {
        if (!landmarks.containsKey(type) || !landmarks.get(type).containsKey(pos)) return;
        Multimap<LandmarkType<?>, BlockPos> changed = landmarks.get(type).get(pos).remove(HashMultimap.create(), world, this);
        handleChanged(world, changed, false, null);
    }

    public void remove(ServerPlayerEntity sender, ServerWorld world, LandmarkType<?> type, BlockPos pos) {
        if (!landmarks.containsKey(type) || !landmarks.get(type).containsKey(pos)) return;
        Multimap<LandmarkType<?>, BlockPos> changed = landmarks.get(type).get(pos).remove(HashMultimap.create(), world, this);
        handleChanged(world, changed, false, sender);
    }

    public void removeAll(World world, Class<?> clazz, BlockPos pos) {
        Multimap<LandmarkType<?>, BlockPos> changed = HashMultimap.create();
        landmarks.forEach((type, map) -> {
            if (map.containsKey(pos)) {
                Landmark<?> landmark = map.get(pos);
                if (clazz.isAssignableFrom(landmark.getClass())) {
                    landmark.remove(changed, world, this);
                }
            }
        });
        handleChanged(world, changed, false, null);
    }

    public int save(World world, File folder) {
        if (dirty) {
            File landmarksFile = new File(folder, "landmarks.dat");
            try {
                NbtIo.writeCompressed(Landmarks.writeNbt(landmarks, new NbtCompound()), landmarksFile);
            } catch (IOException e) {
                Surveyor.LOGGER.error("[Surveyor] Error writing landmarks file for {}.", world.getRegistryKey().getValue(), e);
            }
            return landmarks.size();
        }
        return 0;
    }

    public static WorldLandmarks load(World world, File folder) {
        NbtCompound landmarkNbt = new NbtCompound();
        File landmarksFile = new File(folder, "landmarks.dat");
        if (landmarksFile.exists()) {
            try {
                landmarkNbt = NbtIo.readCompressed(landmarksFile);
            } catch (IOException e) {
                Surveyor.LOGGER.error("[Surveyor] Error loading landmarks file for {}.", world.getRegistryKey().getValue(), e);
            }
        }
        return new WorldLandmarks(world.getRegistryKey(), Landmarks.fromNbt(landmarkNbt));
    }
}
