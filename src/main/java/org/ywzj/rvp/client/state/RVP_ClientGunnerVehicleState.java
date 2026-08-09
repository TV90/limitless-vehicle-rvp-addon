package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.entity.gunner.ai.profile.RVP_EnumGunnerFaction;
import org.ywzj.rvp.network.S2CGunnerVehicleSync;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端侧表：远程 Gunner 载具的阵营 / 航向缓存（按 entityId）。
 *
 * <p>替代被删 {@code AbstractVehicleGunnerDataMixin} 注入到远程实体上的
 * {@code rvpRemoteFaction} / 航向字段。数据经 {@link S2CGunnerVehicleSync} 定期推送。</p>
 */
public final class RVP_ClientGunnerVehicleState {

    private record Entry(float yRot, float xRot, RVP_EnumGunnerFaction faction) {}

    @Nullable
    private static ResourceLocation dimension;
    private static final Map<Integer, Entry> ENTRIES = new LinkedHashMap<>();

    private RVP_ClientGunnerVehicleState() {}

    public static void applySnapshot(S2CGunnerVehicleSync msg) {
        dimension = msg.dimension;
        ENTRIES.clear();
        for (S2CGunnerVehicleSync.Entry entry : msg.entries) {
            ENTRIES.put(entry.entityId(), new Entry(entry.yRot(), entry.xRot(), entry.faction()));
        }
        // 航向应用到已加载的远程实体（remoteTick 为空操作，应用一次即可保持）
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        for (S2CGunnerVehicleSync.Entry entry : msg.entries) {
            Entity entity = level.getEntity(entry.entityId());
            if (entity instanceof AbstractVehicle vehicle) {
                vehicle.setYRot(entry.yRot());
                vehicle.setXRot(entry.xRot());
                vehicle.yRotO = entry.yRot();
                vehicle.xRotO = entry.xRot();
            }
        }
    }

    /**
     * 查询远程载具的 Gunner 阵营；非远程 / 未知返回 null。
     */
    @Nullable
    public static RVP_EnumGunnerFaction getFaction(AbstractVehicle vehicle) {
        Entry entry = ENTRIES.get(vehicle.getId());
        return entry == null ? null : entry.faction();
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            clear();
            return;
        }
        ResourceLocation current = level.dimension().location();
        if (dimension != null && !dimension.equals(current)) {
            clear();
            return;
        }
        // 清理已不存在的实体条目，避免残留过期阵营
        ENTRIES.keySet().removeIf(entityId -> level.getEntity(entityId) == null);
    }

    public static void clear() {
        dimension = null;
        ENTRIES.clear();
    }
}
