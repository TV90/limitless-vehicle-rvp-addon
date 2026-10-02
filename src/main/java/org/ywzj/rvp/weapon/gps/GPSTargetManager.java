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

    /** MULTI 模式默认目标点上限（武器未配置 {@code multi_max_points} 或非 GPS 武器写点路径时使用）。 */
    public static final int DEFAULT_MULTI_MAX_POINTS = 8;

    private static PlayerState state(UUID uuid) {
        return TARGETS.computeIfAbsent(uuid, unused -> new PlayerState());
    }

    public static Snapshot clear(ServerPlayer player) {
        PlayerState state = state(player.getUUID());
        state.clearPoints();
        return state.snapshot();
    }

    /** 清除任意实体（含 Gunner）拥有的 GPS 目标。 */
    public static Snapshot clear(Entity entity) {
        PlayerState state = state(entity.getUUID());
        state.clearPoints();
        return state.snapshot();
    }

    public static Snapshot set(ServerPlayer player, ResourceLocation dimension, Vec3 pos) {
        PlayerState state = state(player.getUUID());
        state.setSingle(new GPSTarget(dimension, pos));
        return state.snapshot();
    }

    public static Snapshot set(Entity entity, ResourceLocation dimension, Vec3 pos) {
        PlayerState state = state(entity.getUUID());
        state.setSingle(new GPSTarget(dimension, pos));
        return state.snapshot();
    }

    public static Snapshot add(ServerPlayer player, ResourceLocation dimension, Vec3 pos) {
        PlayerState state = state(player.getUUID());
        state.addPoint(new GPSTarget(dimension, pos), DEFAULT_MULTI_MAX_POINTS);
        return state.snapshot();
    }

    /** MULTI 追加点（带武器配置的滑动窗口上限）；非 MULTI 模式退化为覆盖单点。 */
    public static Snapshot add(Entity entity, ResourceLocation dimension, Vec3 pos, int maxPoints) {
        PlayerState state = state(entity.getUUID());
        state.addPoint(new GPSTarget(dimension, pos), maxPoints);
        return state.snapshot();
    }

    /**
     * [RVP] FAST 模式专用：写单点但<b>保持当前模式（FAST）</b>——{@code set} 会强制 SINGLE
     * 把玩家踢出 FAST；开火键每次点击经此覆盖装订点，发射链照常消费。
     */
    public static Snapshot fastSet(Entity entity, ResourceLocation dimension, Vec3 pos) {
        PlayerState state = state(entity.getUUID());
        state.fastSet(new GPSTarget(dimension, pos));
        return state.snapshot();
    }

    /** 当前玩家是否处于指定 GPS 模式（轻量查询，不出快照）。 */
    public static boolean isMode(Entity entity, RVP_ClientGPSState.Mode mode) {
        PlayerState state = TARGETS.get(entity.getUUID());
        return state != null && state.mode == mode;
    }

    public static Snapshot applyCurrentMode(ServerPlayer player, ResourceLocation dimension, Vec3 pos) {
        PlayerState state = state(player.getUUID());
        if (state.mode == RVP_ClientGPSState.Mode.MULTI) {
            state.addPoint(new GPSTarget(dimension, pos), DEFAULT_MULTI_MAX_POINTS);
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

        /** FAST 专用：覆盖为单点但保持当前模式（FAST），发射链按 SINGLE 语义消费 [0]。 */
        private void fastSet(GPSTarget target) {
            points.clear();
            points.add(target);
            nextIndex = 0;
        }

        /** MULTI 追加；达上限时滑动窗口——移除最旧（index 0）、新点成末位、其余前移。 */
        private void addPoint(GPSTarget target, int maxPoints) {
            if (mode != RVP_ClientGPSState.Mode.MULTI) {
                setSingle(target);
                return;
            }
            int cap = Math.max(1, maxPoints);
            while (points.size() >= cap) {
                points.remove(0);
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
            // 进入 FAST/RADAR：清空全部 GPS 点（两模式各自重新取点）
            if (newMode == RVP_ClientGPSState.Mode.FAST || newMode == RVP_ClientGPSState.Mode.RADAR) {
                clearPoints();
                mode = newMode;
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
