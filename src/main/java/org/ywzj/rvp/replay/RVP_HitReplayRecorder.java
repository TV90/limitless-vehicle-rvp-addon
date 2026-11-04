package org.ywzj.rvp.replay;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.network.S2CRvpHitIndicator;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.*;

/** 最近一秒的服务端历史；不强载区块，不扫描世界方块，只有命中才发送。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID)
public final class RVP_HitReplayRecorder {
    /** 弱键不延长实体寿命；离开世界的记录在下一刻移除。 */
    private static final Map<AbstractVehicle, RVP_HitReplay.Window<RVP_HitReplay.Frame>> VEHICLES = new WeakHashMap<>();
    /** 仅记录玩家发射的投射物，AI 弹幕不占历史缓存。 */
    private static final Map<Projectile, RVP_HitReplay.Window<RVP_HitReplay.Point>> PROJECTILES = new WeakHashMap<>();
    /** 每位射手只保持当前目标的发送游标，换目标即完整发送；避免客户端缓存淘汰协议。 */
    private static final Map<ServerPlayer, Cursor> SENT = new WeakHashMap<>();
    /** 记录对象本身保证跨维度/UUID/重生不会复用游标。 */
    private record Cursor(AbstractVehicle target, long tick) {}
    private RVP_HitReplayRecorder() {}

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof AbstractVehicle vehicle)
            VEHICLES.put(vehicle, new RVP_HitReplay.Window<>());
        if (event.getEntity() instanceof Projectile projectile && projectile.getOwner() instanceof ServerPlayer)
            PROJECTILES.put(projectile, new RVP_HitReplay.Window<>());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTick(TickEvent.LevelTickEvent event) {
        if (event.level.isClientSide()) return;
        long tick = event.level.getGameTime();
        if (event.phase == TickEvent.Phase.START) {
            PROJECTILES.entrySet().removeIf(entry -> entry.getKey().isRemoved());
            PROJECTILES.forEach((projectile, history) -> {
                if (projectile.level() == event.level)
                    history.put(tick - 1, new RVP_HitReplay.Point(tick - 1, projectile.position()));
            });
        } else {
            VEHICLES.entrySet().removeIf(entry -> entry.getKey().isRemoved());
            VEHICLES.forEach((vehicle, history) -> {
                if (vehicle.level() == event.level && !vehicle.getPartUnits().isEmpty())
                    history.put(tick, RVP_ReplayPose.capture(vehicle, tick));
            });
            SENT.entrySet().removeIf(entry -> entry.getKey().isRemoved() || entry.getValue().target().isRemoved()
                    || entry.getKey().level() != entry.getValue().target().level());
        }
    }

    /** RVP 弹体刻起点补采样，覆盖当刻才出生以及虚拟弹道恢复的实体。 */
    public static void recordProjectile(Projectile projectile) {
        if (projectile.level().isClientSide() || !(projectile.getOwner() instanceof ServerPlayer)) return;
        long tick = projectile.level().getGameTime() - 1;
        PROJECTILES.computeIfAbsent(projectile, key -> new RVP_HitReplay.Window<>())
                .put(tick, new RVP_HitReplay.Point(tick, projectile.position()));
    }

    /** 给既有命中包附加增量历史，原有伤害/标题/命中开关逻辑保持同一出口。 */
    public static S2CRvpHitIndicator attach(S2CRvpHitIndicator message, ServerPlayer shooter,
                                          Entity target, Projectile projectile) {
        if (!(target instanceof AbstractVehicle vehicle) || vehicle.getVehicleId() == null) return message;
        long tick = vehicle.level().getGameTime();
        var history = VEHICLES.computeIfAbsent(vehicle, key -> new RVP_HitReplay.Window<>());
        history.put(tick, RVP_ReplayPose.capture(vehicle, tick));
        Cursor last = SENT.get(shooter);
        boolean reset = last == null || last.target() != vehicle || tick - last.tick() > 60 || tick < last.tick();
        // 同 tick 可有直击与爆炸，允许覆盖这一帧的受损状态，但不重发前一秒。
        List<RVP_HitReplay.Frame> frames = reset ? history.all() : history.after(last.tick() - 1);
        var path = projectile == null ? null : PROJECTILES.get(projectile);
        List<RVP_HitReplay.Point> points = new ArrayList<>();
        if (path != null) for (var point : path.all()) if (point.tick() >= tick - RVP_HitReplay.HISTORY_TICKS) points.add(point);
        if (!points.isEmpty() && points.get(points.size() - 1).tick() == tick) points.remove(points.size() - 1);
        points.add(new RVP_HitReplay.Point(tick, message.hitPosition));
        var incoming = message.hitVector.lengthSqr() > 1e-8 ? message.hitVector.normalize().scale(-1)
                : new net.minecraft.world.phys.Vec3(0, 0, -1);
        message.replay = new RVP_HitReplay.Clip(vehicle.getUUID(), EntityType.getKey(vehicle.getType()),
                vehicle.getVehicleId(), vehicle.getDisplayId(), tick, reset, frames, points,
                RVP_ReplayPose.anchor(vehicle, message.hitPosition, incoming, message.boneName));
        SENT.put(shooter, new Cursor(vehicle, tick));
        return message;
    }

    @SubscribeEvent
    public static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) return;
        VEHICLES.keySet().removeIf(entity -> entity.level() == event.getLevel());
        PROJECTILES.keySet().removeIf(entity -> entity.level() == event.getLevel());
        SENT.entrySet().removeIf(entry -> entry.getValue().target().level() == event.getLevel());
    }
}
