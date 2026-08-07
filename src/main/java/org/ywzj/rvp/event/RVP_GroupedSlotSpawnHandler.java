package org.ywzj.rvp.event;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_CommonConfig;
import org.ywzj.rvp.vehicle.RVP_GroupedWeaponSlotAssembler;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 组合挂架装配驱动（替代已移除的 {@code WeaponUnitGroupedSlotMixin}）。
 *
 * <p>原 mixin 在 {@code WeaponUnit.combineAndInit} HEAD 拦截并取消本体默认武器组装，改为 RVP 分组组装
 * （{@code merge_into_previous_slot} 槽位合并为外层 {@code VehicleMultiWeapons}）。移除后曾用
 * {@code EntityJoinLevelEvent} 延迟装配，但该事件在服务端新生成载具时 <strong>早于</strong>
 * {@code initData()}（日志实证 partUnits=0），导致装配 no-op——T90M 出现 AP/HE 拆成两个武器栏、
 * F 键只在 AP 组内切换、HE 无法切回 AP、滚轮可在主炮弹种间切武器等一连串问题。</p>
 *
 * <p>本 handler 改为与 {@link RVP_PhysicsOnlyCollisionEventHandler} 同款成熟模式：每 tick
 * （服务端 / 客户端各一）遍历载具，以 {@code centerOffset != null}（initData 完成标志）为门槛，
 * {@code WeakHashMap<AbstractVehicle, Boolean>} 一次性守卫执行装配，双端都可靠生效。
 * 服务端装配会替换武器对象（新实例弹药为 0），故装配完成后按 {@code spawnVehicleWithCreativeAmmo}
 * 配置重新补满一次弹药。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_GroupedSlotSpawnHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 已装配过的载具（key = 实体实例）。WeakHashMap：单机下服务端实体与客户端实体是不同实例
     * 但 UUID 相同，按 UUID 标记会互相误判跳过；实体被 GC 后条目自动清理。
     */
    private static final Map<AbstractVehicle, Boolean> ASSEMBLED = Collections.synchronizedMap(new WeakHashMap<>());

    private RVP_GroupedSlotSpawnHandler() {}

    @SubscribeEvent
    public static void onEntityLeaveWorld(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof AbstractVehicle vehicle) {
            ASSEMBLED.remove(vehicle);
        }
    }

    private static void assembleIfPending(AbstractVehicle vehicle) {
        // initData 完成标志：centerOffset 由 initData(BaseVehicleData) 填充，
        // 非 null 说明 partUnits/weapons 已由本体 combineAndInit 构建完毕，可安全重装配。
        if (vehicle.centerOffset == null) {
            return;
        }
        if (ASSEMBLED.put(vehicle, Boolean.TRUE) != null) {
            return;
        }
        boolean assembled = false;
        try {
            Map<String, PartUnit<?>> partUnitsView = new HashMap<>();
            for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
                partUnitsView.put(partUnit.getId(), partUnit);
            }
            for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
                if (partUnit instanceof WeaponUnit weaponUnit
                        && RVP_GroupedWeaponSlotAssembler.shouldHandle(weaponUnit)) {
                    RVP_GroupedWeaponSlotAssembler.assemble(weaponUnit, partUnitsView, vehicle);
                    assembled = true;
                }
            }
        } catch (Throwable t) {
            // 防御：分组装配失败不允许阻断实体
            LOGGER.error("[RVP-GroupedSlot] 载具 {} 分组装配异常（已忽略，不阻断生成）", vehicle.getVehicleId(), t);
        }
        if (assembled && !vehicle.level().isClientSide()
                && RVP_CommonConfig.isSpawnVehicleWithCreativeAmmo()) {
            // 装配替换了武器对象：EntityJoinLevelEvent 时补满的是旧实例，重新补满一次
            RVP_VehicleSpawnHandler.refillAllWeapons(vehicle);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (entity instanceof AbstractVehicle vehicle) {
                    assembleIfPending(vehicle);
                }
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof AbstractVehicle vehicle) {
                assembleIfPending(vehicle);
            }
        }
    }
}
