package org.ywzj.rvp.client.state;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BakedModelInstance;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.runtime.BoneState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.client.render.RVP_DistanceBoneHider;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.misc.VehiclePart;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 残件"击毁前状态"骨快照缓存（2026-10-08，残件隐藏渲染骨的动态增强）。
 *
 * <p>残件（VehiclePart 飞头）不跑动画控制器，源车 JS 脚本造成的"损坏不渲染"
 * （{@code pose.hideBone} → scale 0 → 既有 Mixin 转写为 {@code BoneState.visible=false}）
 * 在残件自己的模型实例上不会重现，被击毁前已打坏的炮塔爆反/炮管会在残件上"复活"。
 * 本缓存在残件首次渲染时<b>借源</b>：在附近找同载具类型、已击毁、距离最近的源车残骸
 * （残件即从它身上飞出，位置天然相近；排除其它残件自身），一次拍取源车模型实例上
 * {@code visible == false} 的骨序号集合（剔除距离 LOD 的临时假值），此后残件渲染按
 * 骨序号应用隐藏——残件与源车共用同一 bedrock 模型，骨序一一对应。</p>
 *
 * <p>边界：同型多车贴脸同时击毁可能借错源（后果=使用同型车的隐藏集，骨集基本相同）；
 * 源车已卸载则拍得空集，降级为不自动隐藏（与无本缓存时的现状一致）。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ClientWreckHiddenBonesCache {

    /** 残件实体 id → 源车击毁前隐藏骨序号快照；拍取一次后不再刷新（源车死亡后隐藏集不恢复）。 */
    private static final Map<Integer, Set<Integer>> SNAPSHOT = new HashMap<>();

    /** 缓存安全上限，防止极端战场残件数量下无限累积。 */
    private static final int MAX_ENTRIES = 256;

    /** 借源诊断日志（定位后移除）。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    private RVP_ClientWreckHiddenBonesCache() {
    }

    /**
     * 取该残件的源车隐藏骨序号快照（无则借源拍取一次）。
     *
     * @param part 残件实体
     * @return 源车击毁前 {@code visible == false} 的骨序号集合；借源失败返回空集
     */
    public static Set<Integer> snapshotFor(VehiclePart part) {
        int partId = part.getId();
        Set<Integer> cached = SNAPSHOT.get(partId);
        if (cached != null) {
            return cached;
        }
        AbstractVehicle source = findNearestDestroyedSource(part);
        Set<Integer> bones = source == null ? Set.of() : capture(source);
        if (SNAPSHOT.size() >= MAX_ENTRIES) {
            SNAPSHOT.clear();
        }
        SNAPSHOT.put(partId, bones);
        // 诊断日志（2026-10-08 借源链路排查，定位后移除）：残件借源结果与拍取内容
        LOGGER.info("[RVP-WreckBones] part={} vehicleId={} source={} captured={} bones={}",
                partId, part.getVehicleId(),
                source == null ? "NONE" : source.getId() + "/" + source.getUUID(),
                bones.size(),
                source == null ? "-" : describe(source, bones));
        return bones;
    }

    /** 诊断用：按骨序号拼接快照内容（借源日志专用，不影响热路径）。 */
    private static String describe(AbstractVehicle source, Set<Integer> indexes) {
        BakedModelInstance instance = source.getVehicleModelInstance();
        if (instance == null) {
            return "no-instance";
        }
        StringBuilder sb = new StringBuilder("[");
        for (Integer index : indexes) {
            BoneState bone = instance.getBone(index);
            sb.append(bone == null ? String.valueOf(index) : "?" + index).append(',');
        }
        return sb.append(']').toString();
    }

    /**
     * 借源：找同载具类型、已击毁、距残件最近的源车残骸（排除其它残件自身——残件同为
     * AbstractVehicle 子类且也处于 destroyed 态，必须显式排除才能借到真正的整车残骸）。
     */
    private static AbstractVehicle findNearestDestroyedSource(VehiclePart part) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        AbstractVehicle nearest = null;
        double nearestDistSqr = Double.MAX_VALUE;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof AbstractVehicle vehicle)
                    || entity == part
                    || entity instanceof VehiclePart
                    || !vehicle.isDestroyed()
                    || !vehicle.isAlive()) {
                continue;
            }
            if (!vehicle.getVehicleId().equals(part.getVehicleId())) {
                continue;
            }
            double distSqr = entity.distanceToSqr(part);
            if (distSqr < nearestDistSqr) {
                nearestDistSqr = distSqr;
                nearest = vehicle;
            }
        }
        return nearest;
    }

    /** 拍取源车模型实例上 visible==false 的骨序号集合；距离 LOD 的临时假值不采集。 */
    private static Set<Integer> capture(AbstractVehicle source) {
        BakedModelInstance instance = source.getVehicleModelInstance();
        if (instance == null) {
            return Set.of();
        }
        BoneState[] bones = instance.getBoneIndexes();
        Set<Integer> hidden = new HashSet<>();
        for (int index = 0; index < bones.length; index++) {
            BoneState bone = bones[index];
            if (bone == null || bone.visible) {
                continue;
            }
            // 距离 LOD（distance_hidden_bones）按相机距离每帧重写，属临时假值：
            // 残件渲染距离与源车无关，不采集（用距离隐藏器的当前真值查询判定）
            if (RVP_DistanceBoneHider.isBoneIndexHidden(instance, index)) {
                continue;
            }
            hidden.add(index);
        }
        return Set.copyOf(hidden);
    }

    /** 残件实体离开世界时释放快照，防止战场残件 id 长期累积。 */
    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        SNAPSHOT.remove(event.getEntity().getId());
    }
}
