package org.ywzj.rvp.mount;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_CustomMountConfig;
import org.ywzj.rvp.config.RVP_CustomMountConfigCache;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.Bolt;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [RVP] 自定义挂架出弹队列应用器（零 Mixin：Forge 事件 + 公共 API）。
 *
 * <p>把 {@link RVP_ShootBoltQueueResolver} 构建的出弹 Bolt 队列写入载具武器站：
 * 通过公共 {@code WeaponUnit.getBolts()} 活引用做 {@code clear()/addAll()}——
 * 与本体 {@code Ztz99a}/{@code Z10} 构造器内改发射点的既有模式一致，不触碰
 * 私有字段、不新增 Mixin（agents.md Mixin 纪律）。</p>
 *
 * <p>时序说明：服务端首次生成载具时 {@code initData()}（构建 partUnits）在
 * {@code onAddedToWorld()} 触发，晚于 {@code EntityJoinLevelEvent}，故采用
 * "join 挂起 + tick 排空"：事件只登记，tick 中等部件就绪后再应用。</p>
 *
 * <p>双端对称：客户端开火坐标（aimContexts）从客户端单位的 Bolt 计算，因此
 * 客户端与服务端都走同一套挂起/排空；MP 客户端的配置缓存由
 * {@code S2CVehicleRvpConfig} 在登录期填充，早于世界实体生成。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ShootBoltQueueApplier {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 当前武器无条目匹配（回退条目并集）时的武器口径标记。 */
    private static final String KEY_UNION = "<union>";

    /** 等待部件就绪后首次应用出弹队列的载具（join 登记、tick 排空）。 */
    private static final Set<AbstractVehicle> PENDING =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    /**
     * 已应用过出弹队列的载具 → (武器站 id → 应用时武器口径)。
     * 目的：玩家切换弹种后重放对应队列，使出弹点跟随当前武器（对齐消失渲染口径）。
     */
    private static final Map<AbstractVehicle, Map<String, String>> WATCHED = new ConcurrentHashMap<>();

    private RVP_ShootBoltQueueApplier() {}

    /** 目的：载具加入世界时登记（双端）；此时部件可能尚未构建，应用推迟到 tick。 */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof AbstractVehicle vehicle) {
            PENDING.add(vehicle);
        }
    }

    /** 目的：载具离开世界（卸载/死亡/跨维度）时清理跟踪表，防止实体引用滞留。 */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof AbstractVehicle vehicle) {
            PENDING.remove(vehicle);
            WATCHED.remove(vehicle);
        }
    }

    /**
     * 目的：tick 中排空挂起集合并对已应用载具做换弹种检测。
     * 仅在 Phase.END 处理一次；双端各自的 Level 都会触发，逻辑幂等。
     */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (PENDING.isEmpty() && WATCHED.isEmpty()) {
            return;
        }
        // 目的：快照遍历 + 边遍历边摘除（ConcurrentHashMap 迭代器弱一致，安全）
        for (AbstractVehicle vehicle : PENDING) {
            if (tryApplyVehicle(vehicle)) {
                PENDING.remove(vehicle);
            }
        }
        for (Map.Entry<AbstractVehicle, Map<String, String>> entry : WATCHED.entrySet()) {
            AbstractVehicle vehicle = entry.getKey();
            if (vehicle.isRemoved()) {
                WATCHED.remove(vehicle);
                continue;
            }
            refreshStationQueues(vehicle, entry.getValue());
        }
    }

    /**
     * 尝试对载具应用出弹队列。
     *
     * @return true = 本载具处理完毕（已应用或无需应用），可移出挂起集合；
     *         false = 部件/数据未就绪，下 tick 重试
     */
    private static boolean tryApplyVehicle(AbstractVehicle vehicle) {
        if (vehicle.isRemoved()) {
            return true;
        }
        ResourceLocation vehicleId = vehicle.getVehicleId();
        if (vehicleId == null) {
            // initData() 尚未执行（部件未构建），下 tick 重试
            return false;
        }
        // 目的：读取本载具的挂架条目；无任何条目（未配置自定义挂架）时直接放行，
        // 对未配置载具零开销、零行为影响
        List<RVP_CustomMountConfig> configs = RVP_CustomMountConfigCache.get(vehicleId);
        if (configs.isEmpty()) {
            return true;
        }
        Map<String, String> weaponKeys = WATCHED.computeIfAbsent(vehicle, key -> new ConcurrentHashMap<>());
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit station)) {
                continue;
            }
            if (!hasShootStructureBones(configs, station)) {
                continue;
            }
            applyStationQueue(vehicle, station, weaponKeys);
        }
        return true;
    }

    /** 目的：换弹种检测——当前武器口径与应用时不一致则重放该站出弹队列。 */
    private static void refreshStationQueues(AbstractVehicle vehicle, Map<String, String> weaponKeys) {
        for (Map.Entry<String, String> entry : weaponKeys.entrySet()) {
            // 目的：按部件 id 取回武器站实例（不在 partUnits 列表中的站已失效，跳过）
            if (vehicle.getPartUnit(entry.getKey()).orElse(null) instanceof WeaponUnit station) {
                String currentKey = RVP_ShootBoltQueueResolver.currentWeaponKey(station);
                if (!currentKey.equals(entry.getValue())) {
                    applyStationQueue(vehicle, station, weaponKeys);
                }
            }
        }
    }

    /**
     * 对单个武器站应用出弹队列：公共活引用 {@code getBolts()} 改写 +
     * {@code countFire(0)} 归位轮转索引（防止旧索引越界，见方法内注释）。
     */
    private static void applyStationQueue(AbstractVehicle vehicle, WeaponUnit station,
                                          Map<String, String> weaponKeys) {
        List<Bolt> queue = RVP_ShootBoltQueueResolver.buildQueue(vehicle, station);
        String weaponKey = queue == null
                ? KEY_UNION
                : RVP_ShootBoltQueueResolver.currentWeaponKey(station);
        // 目的：无论本次是否成功构建，都记录口径，避免 tick 内对同一状态反复重放
        weaponKeys.put(station.getId(), weaponKey);
        if (queue == null) {
            LOGGER.warn("[RVP] 出弹队列构建失败，保留本体原 Bolt vehicle={} station={}",
                    vehicle.getVehicleId(), station.getId());
            return;
        }
        // 目的：公共活引用整体替换出弹点（本体 Ztz99a/Z10 同款模式，不触碰数据模板）
        List<Bolt> bolts = station.getBolts();
        bolts.clear();
        bolts.addAll(queue);
        // 目的：队列长度变化后，轮转索引可能超出新范围，countFire(0) 做一次
        // 纯索引取模归位（不改弹药、不改状态），防止 getCurrentBolt 越界
        station.countFire(0);
    }

    /** 目的：判断武器站是否有配置出弹骨的条目（无则完全不受本功能影响）。 */
    private static boolean hasShootStructureBones(List<RVP_CustomMountConfig> configs, WeaponUnit station) {
        for (RVP_CustomMountConfig config : configs) {
            if (!config.shootStructureBones().isEmpty()
                    && RVP_ShootBoltQueueResolver.matchesStation(station, config.partUnitId())) {
                return true;
            }
        }
        return false;
    }
}
