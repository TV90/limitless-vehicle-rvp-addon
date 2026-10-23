package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.network.S2CRemoteAmmoSnapshot;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RVP_ClientRemoteAmmoState {

    private static final Map<Integer, S2CRemoteAmmoSnapshot.Entry> ENTRIES = new LinkedHashMap<>();
    /** 仅供显示的跨快照位置过渡，实体消失或切维度时随快照状态一同清理。 */
    private static final Map<Integer, PositionTransition> POSITIONS = new LinkedHashMap<>();
    /** 最近快照的客户端世界时间，单位 Tick；无样本时为最小 long。 */
    private static long snapshotGameTime = Long.MIN_VALUE;
    @Nullable
    private static ResourceLocation dimension;

    private RVP_ClientRemoteAmmoState() {}

    public static void applySnapshot(S2CRemoteAmmoSnapshot msg) {
        Minecraft mc = Minecraft.getInstance();
        long gameTime = mc.level != null ? mc.level.getGameTime() : 0L;
        if (!msg.dimension.equals(dimension) || gameTime < snapshotGameTime) {
            // 调用本类清理入口，防止切维度或时间回退后沿用旧弹体轨迹。
            clear();
        }
        long interval = snapshotGameTime == Long.MIN_VALUE ? 1L : Math.max(1L, gameTime - snapshotGameTime);
        dimension = msg.dimension;
        ENTRIES.clear();
        for (S2CRemoteAmmoSnapshot.Entry entry : msg.entries) {
            ENTRIES.put(entry.entityId(), entry);
            Vec3 target = new Vec3(entry.x(), entry.y(), entry.z());
            PositionTransition previous = POSITIONS.get(entry.entityId());
            // 复用广播载具的连续接续方式：从旧过渡在收包时刻的位置，线性插值到新样本。
            Vec3 start = previous != null ? previous.positionAt(gameTime) : target;
            POSITIONS.put(entry.entityId(), new PositionTransition(start, target, gameTime, interval));
        }
        POSITIONS.keySet().retainAll(ENTRIES.keySet());
        snapshotGameTime = gameTime;
    }

    /** 解析只有弹药快照、没有客户端实体时的连续显示位置；不推测快照未提供的速度方向。 */
    public static Vec3 resolveRenderPosition(S2CRemoteAmmoSnapshot.Entry entry, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        PositionTransition transition = POSITIONS.get(entry.entityId());
        if (mc.level == null || !mc.level.dimension().location().equals(dimension)
                || ENTRIES.get(entry.entityId()) != entry || transition == null) {
            return new Vec3(entry.x(), entry.y(), entry.z());
        }
        // 调用本类位置过渡，复用 Minecraft 线性插值并把进度限定在当前样本区间。
        return transition.positionAt(mc.level.getGameTime() + Mth.clamp(partialTick, 0.0f, 1.0f));
    }

    public static Collection<S2CRemoteAmmoSnapshot.Entry> getEntries(@Nullable ResourceLocation currentDimension) {
        if (currentDimension == null || dimension == null || !dimension.equals(currentDimension)) {
            return java.util.List.of();
        }
        return ENTRIES.values();
    }

    @Nullable
    public static S2CRemoteAmmoSnapshot.Entry getEntry(@Nullable ResourceLocation currentDimension, int entityId) {
        if (currentDimension == null || dimension == null || !dimension.equals(currentDimension)) {
            return null;
        }
        return ENTRIES.get(entityId);
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            clear();
            return;
        }
        ResourceLocation levelDimension = mc.level.dimension().location();
        if (dimension != null && !dimension.equals(levelDimension)) {
            clear();
        }
    }

    public static void clear() {
        dimension = null;
        ENTRIES.clear();
        POSITIONS.clear();
        snapshotGameTime = Long.MIN_VALUE;
    }

    /**
     * 单份快照的显示过渡，不修改服务器提供的弹体位置。
     * @param start 上一过渡在收包时刻的位置，单位格
     * @param target 本次快照位置，单位格
     * @param sampleTick 本次收包的客户端世界 Tick
     * @param durationTicks 与上一份快照的间隔，至少 1 Tick
     */
    private record PositionTransition(Vec3 start, Vec3 target, long sampleTick, long durationTicks) {
        /** 使用与广播载具相同的线性插值和区间钳制，缺包后停在最新样本位置。 */
        private Vec3 positionAt(double gameTime) {
            return start.lerp(target, Mth.clamp((gameTime - sampleTick) / durationTicks, 0.0D, 1.0D));
        }
    }
}
