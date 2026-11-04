package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.network.S2CVirtualMissileTargets;
import org.ywzj.rvp.virtualflight.server.RVP_VirtualMissileTargets;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 客户端虚拟目标缓存：复用本体广播目标表，支持现有雷达界面与锁定输入，不入世界。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientVirtualMissileState {
    /** 当前权威快照建立的虚拟目标，按会话实体 ID 索引。 */
    private static final Map<Integer, RVP_MissileEntity> TARGETS = new HashMap<>();
    /** 缓存所属的客户端世界实例；同维度重开世界也必须清空。 */
    @Nullable private static ClientLevel level;
    /** 最近完整集合的接收游戏刻，丢包或退出载具后不能无限保留目标。 */
    private static long lastSnapshotTick;
    /** 最长允许 1 秒没有新快照，单位游戏刻。 */
    private static final int SNAPSHOT_TIMEOUT_TICKS = 20;

    /** 工具类不允许实例化。 */
    private RVP_ClientVirtualMissileState() {}

    /** 应用完整集合；同 ID/UUID 的对象持续复用，避免每次收包都重置锁定引用。 */
    public static void accept(S2CVirtualMissileTargets message) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel current = minecraft.level;
        LocalVehiclePlayer player = LocalVehiclePlayer.instance;
        if (current == null || !current.dimension().location().equals(message.dimension())
                || player == null || player.getPlayer() == null) return;
        if (level != current) clear();
        level = current;
        lastSnapshotTick = current.getGameTime();
        Set<Integer> incoming = new HashSet<>();
        for (S2CVirtualMissileTargets.Entry entry : message.entries()) {
            if (!entry.data().hasUUID("UUID")) continue;
            incoming.add(entry.entityId());
            RVP_MissileEntity target = TARGETS.get(entry.entityId());
            LocalVehiclePlayer.ServerEntity broadcast = player.serverEntities.get(entry.entityId());
            if (target == null || !target.isAlive() || !target.getUUID().equals(entry.data().getUUID("UUID"))) {
                if (target != null) retire(target, false);
                target = new RVP_MissileEntity(RVP_Entities.RVP_MISSILE.get(), current);
            }
            target.load(entry.data());
            target.setId(entry.entityId());
            // 调用本体远程弹体数据入口，恢复分角度信号、武器标识及发射者引用。
            target.readData(entry.data());
            target.setVirtualMidcourseTarget(true);
            target.setVirtualMidcourseGroundHeight(entry.groundHeight());
            TARGETS.put(entry.entityId(), target);
            Entity local = current.getEntity(entry.entityId());
            if (local != null && local.getUUID().equals(target.getUUID())) {
                // 调用共用交接入口，在真实实体移除包到来前保留已存在的接触与硬锁。
                RVP_VirtualMissileTargets.replaceReferences(current.entitiesForRendering(), local, target);
            }
            if (broadcast != null && broadcast.entity != null && broadcast.entity != target
                    && broadcast.entity.getUUID().equals(target.getUUID())) {
                RVP_VirtualMissileTargets.replaceReferences(current.entitiesForRendering(), broadcast.entity, target);
            }
            LocalVehiclePlayer.ServerEntity replacement = new LocalVehiclePlayer.ServerEntity();
            replacement.entity = target;
            replacement.updateTick = player.getPlayer().tickCount;
            player.serverEntities.put(entry.entityId(), replacement);
        }
        for (var entry : Map.copyOf(TARGETS).entrySet()) {
            if (!incoming.contains(entry.getKey())) {
                // 调用退出入口，恢复实体与拦截销毁分别处理，避免恢复时闪断和击毁后残影。
                retire(entry.getValue(), message.restoredIds().contains(entry.getKey()));
                TARGETS.remove(entry.getKey());
            }
        }
    }

    /** 虚拟快照优先于尚未收到移除包的旧真实实体。 */
    @Nullable
    public static Entity target(int entityId) {
        RVP_MissileEntity target = TARGETS.get(entityId);
        return target != null && target.isAlive() && target.level() == Minecraft.getInstance().level ? target : null;
    }

    /** 停止发送、退服或重开世界时撤销旧目标，防止幽灵锁定。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || level == null) return;
        if (Minecraft.getInstance().level != level || level.getGameTime() - lastSnapshotTick > SNAPSHOT_TIMEOUT_TICKS) clear();
    }

    /** 移除目标或交还本体广播接管；只有明确恢复时才允许保留远程表示。 */
    private static void retire(RVP_MissileEntity target, boolean restored) {
        LocalVehiclePlayer player = LocalVehiclePlayer.instance;
        Entity local = level == null ? null : level.getEntity(target.getId());
        boolean localMatches = local != null && local.isAlive() && local.getUUID().equals(target.getUUID());
        if (restored && !localMatches) {
            // 恢复在原生追踪范围外时，暂交回本体广播表；其正常更新/超时机制继续接管。
            target.setVirtualMidcourseTarget(false);
            target.setVirtualMidcourseGroundHeight(null);
            return;
        }
        if (level != null && level == Minecraft.getInstance().level) {
            // 调用统一引用交接/撤销，客户端雷达、武器站和在途弹体一起更新。
            RVP_VirtualMissileTargets.replaceReferences(level.entitiesForRendering(), target,
                    restored && localMatches ? local : null);
        }
        if (player != null) {
            LocalVehiclePlayer.ServerEntity broadcast = player.serverEntities.get(target.getId());
            if (broadcast != null && broadcast.entity == target) player.serverEntities.remove(target.getId(), broadcast);
        }
        target.setRemoved(Entity.RemovalReason.DISCARDED);
    }

    /** 清理仅属本缓存的广播对象，不删除本体正在维护的其他真实远程实体。 */
    public static void clear() {
        for (RVP_MissileEntity target : TARGETS.values()) retire(target, false);
        TARGETS.clear();
        level = null;
    }
}
