package org.ywzj.rvp.weapon.gps;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.state.RVP_ClientGPSState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GPSTargetManager {

    public record Snapshot(RVP_ClientGPSState.Mode mode, List<GPSTarget> points, int nextIndex) {}

    private static final Map<UUID, PlayerState> TARGETS = new ConcurrentHashMap<>();

    private static PlayerState state(UUID uuid) {
        return TARGETS.computeIfAbsent(uuid, unused -> new PlayerState());
    }

    public static Snapshot clear(ServerPlayer player) {
        PlayerState state = state(player.getUUID());
        state.clearPoints();
        return state.snapshot();
    }

    public static Snapshot set(ServerPlayer player, ResourceLocation dimension, Vec3 pos) {
        PlayerState state = state(player.getUUID());
        state.setSingle(new GPSTarget(dimension, pos));
        return state.snapshot();
    }

    public static Snapshot add(ServerPlayer player, ResourceLocation dimension, Vec3 pos) {
        PlayerState state = state(player.getUUID());
        state.addPoint(new GPSTarget(dimension, pos));
        return state.snapshot();
    }

    public static Snapshot applyCurrentMode(ServerPlayer player, ResourceLocation dimension, Vec3 pos) {
        PlayerState state = state(player.getUUID());
        if (state.mode == RVP_ClientGPSState.Mode.MULTI) {
            state.addPoint(new GPSTarget(dimension, pos));
        } else {
            state.setSingle(new GPSTarget(dimension, pos));
        }
        return state.snapshot();
    }

    public static Snapshot setMode(ServerPlayer player, RVP_ClientGPSState.Mode mode) {
        PlayerState state = state(player.getUUID());
        state.setMode(mode);
        return state.snapshot();
    }

    public static Snapshot snapshot(@Nullable Entity entity) {
        if (entity == null) {
            return new Snapshot(RVP_ClientGPSState.Mode.SINGLE, List.of(), 0);
        }
        PlayerState state = TARGETS.get(entity.getUUID());
        return state == null ? new Snapshot(RVP_ClientGPSState.Mode.SINGLE, List.of(), 0) : state.snapshot();
    }

    @Nullable
    public static GPSTarget consumeAssignedTarget(Entity entity, ResourceLocation currentDimension) {
        PlayerState state = TARGETS.get(entity.getUUID());
        if (state == null) {
            return null;
        }
        return state.consume(currentDimension);
    }

    private static final class PlayerState {
        private RVP_ClientGPSState.Mode mode = RVP_ClientGPSState.Mode.SINGLE;
        private final List<GPSTarget> points = new ArrayList<>();
        private int nextIndex;

        private void clearPoints() {
            points.clear();
            nextIndex = 0;
        }

        private void setSingle(GPSTarget target) {
            mode = RVP_ClientGPSState.Mode.SINGLE;
            points.clear();
            points.add(target);
            nextIndex = 0;
        }

        private void addPoint(GPSTarget target) {
            if (mode != RVP_ClientGPSState.Mode.MULTI) {
                setSingle(target);
                return;
            }
            points.add(target);
            nextIndex = normalize(nextIndex);
        }

        private void setMode(RVP_ClientGPSState.Mode newMode) {
            if (newMode == null || newMode == mode) {
                return;
            }
            if (newMode == RVP_ClientGPSState.Mode.SINGLE) {
                GPSTarget keep = points.isEmpty() ? null : points.get(points.size() - 1);
                points.clear();
                if (keep != null) {
                    points.add(keep);
                }
                nextIndex = 0;
                mode = RVP_ClientGPSState.Mode.SINGLE;
                return;
            }
            mode = RVP_ClientGPSState.Mode.MULTI;
            nextIndex = normalize(nextIndex);
        }

        @Nullable
        private GPSTarget consume(ResourceLocation currentDimension) {
            if (points.isEmpty()) {
                return null;
            }
            int index = normalize(nextIndex);
            GPSTarget target = points.get(index);
            if (!target.dimension().equals(currentDimension)) {
                return null;
            }
            if (mode == RVP_ClientGPSState.Mode.MULTI) {
                nextIndex = normalize(index + 1);
            }
            return target;
        }

        private Snapshot snapshot() {
            return new Snapshot(mode, List.copyOf(points), normalize(nextIndex));
        }

        private int normalize(int index) {
            if (points.isEmpty()) {
                return 0;
            }
            int wrapped = index % points.size();
            return wrapped < 0 ? wrapped + points.size() : wrapped;
        }
    }
}
