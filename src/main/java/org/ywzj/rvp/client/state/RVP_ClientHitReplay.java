package org.ywzj.rvp.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.replay.RVP_HitReplay;
import org.ywzj.rvp.replay.RVP_ReplayPose;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.*;

/** 展板专用历史与独立代理：代理不加入 ClientLevel，真实车辆不被临时挪动。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, value = Dist.CLIENT)
public final class RVP_ClientHitReplay {
    /** 当前目标的有限历史，连续射击只合并服务器的新增样本。 */
    private static final NavigableMap<Long, RVP_HitReplay.Frame> FRAMES = new TreeMap<>();
    /** 缓存排序列表，只有来包时重建，不在每帧复制树。 */
    private static List<RVP_HitReplay.Frame> samples = List.of();
    /** 元数据与客户端世界身份用于拒绝跨目标/换世界串帧。 */
    private static RVP_HitReplay.Clip metadata;
    private static Object world;
    private static AbstractVehicle proxy;
    /** 服务器 tick 与本地单调时钟的回放起点；首帧从命中前一秒开始。 */
    private static double startTick;
    private static long startNanos;
    /** 当前渲染姿态供骨骼模块的局部渲染作用域读取。 */
    private static RVP_HitReplay.Frame rendered;
    private RVP_ClientHitReplay() {}

    public static void accept(RVP_HitReplay.Clip clip) {
        if (clip == null) { clear(); return; }
        Object level = Minecraft.getInstance().level;
        boolean reset = metadata == null || !metadata.target().equals(clip.target()) || level != world || clip.reset();
        if (reset) {
            clear();
            world = level;
        }
        for (var frame : clip.frames()) FRAMES.put(frame.tick(), frame);
        FRAMES.headMap(clip.impactTick() - 60, false).clear();
        samples = List.copyOf(FRAMES.values());
        if (reset) {
            // 展示飞行段固定为一秒；历史不足时 sample 保持最早可用姿态，不能把慢放压成瞬间命中。
            startTick = clip.impactTick() - RVP_ClientHitIndicatorState.FLY_MS / 50.0;
            startNanos = System.nanoTime();
        }
        if (metadata != null && (!metadata.vehicleId().equals(clip.vehicleId())
                || !metadata.displayId().equals(clip.displayId()))) proxy = null;
        metadata = clip;
    }

    /** 效果时钟继续推进，但车辆姿态在最新命中帧停止，不读取命中后的实时车辆。 */
    public static double clock() { return startTick + (System.nanoTime() - startNanos) / 50_000_000.0; }

    public static boolean matches(RVP_HitReplay.Clip clip) {
        return clip != null && metadata != null && metadata.target().equals(clip.target());
    }

    public static long elapsed(RVP_ClientHitIndicatorState.HitEvent event) {
        return matches(event.replay) ? Math.round((clock() - event.replay.impactTick()) * 50) + RVP_ClientHitIndicatorState.FLY_MS
                : System.currentTimeMillis() - event.hitTime;
    }

    public static Entity entity(int entityId) {
        var level = Minecraft.getInstance().level;
        if (metadata == null || level == null || world != level || samples.isEmpty()) return null;
        rendered = RVP_HitReplay.sample(samples, Math.min(clock(), metadata.impactTick()));
        // 回放目标被修复/重置时重建代理，避免原生不可逆 destroyed 标记残留。
        if (proxy != null && proxy.isDestroyed() && !rendered.destroyed()) proxy = null;
        if (proxy == null) {
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(metadata.entityType()).orElse(null);
            if (type == null || !(type.create(level) instanceof AbstractVehicle created)) return null;
            proxy = created;
            proxy.setId(entityId);
            proxy.setUUID(metadata.target());
            proxy.setVehicleId(metadata.vehicleId());
            proxy.setDisplayId(metadata.displayId());
            proxy.remote = true;
            // 和已有远距高模代理使用同一组公开初始化入口，不向世界注册实体。
            proxy.initData();
            if (proxy.isRemoved()) { proxy = null; return null; }
            RVP_ReplayPose.apply(proxy, samples.get(0));
            proxy.initDisplayData();
        }
        RVP_ReplayPose.apply(proxy, rendered);
        proxy.tickCount = (int) Math.min(Integer.MAX_VALUE, Math.max(0, Math.min(clock(), metadata.impactTick())));
        return proxy;
    }

    public static boolean isProxy(Entity entity) { return proxy != null && entity == proxy; }

    /** 仅在这一模型的绘制期间切换模块查询，finally 必须结束作用域。 */
    public static void beginRender(Entity entity) {
        if (isProxy(entity) && rendered != null)
            RVP_ClientBoneModuleState.setReplayState(entity.getId(), rendered.inactive());
    }

    @SubscribeEvent
    public static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            clear();
            RVP_ClientHitIndicatorState.clear();
        }
    }

    public static void clear() {
        FRAMES.clear(); samples = List.of(); metadata = null; world = null; proxy = null; rendered = null;
    }
}
