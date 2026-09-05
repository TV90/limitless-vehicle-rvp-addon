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
 * <p>[RVP] 应用策略（重写版）：核心入口是 {@link #ensureApplied(AbstractVehicle, WeaponUnit)}——
 * "用之前拉取确保"，幂等且按（武器口径 × 队列表版本号）判断是否需要重写。
 * 载具 join 时的预热与 miss 重试只作为兜底；开火/干扰物/APS 等读取出弹点之前
 * 调用 {@code ensureApplied} 即可保证使用当前队列表的最新值，不存在时序依赖。</p>
 *
 * <p>双端对称：客户端开火坐标（aimContexts）从客户端单位的 Bolt 计算，因此
 * 客户端与服务端都必须 ensureApplied；MP 客户端的配置缓存由
 * {@code S2CVehicleRvpConfig} 在登录期填充。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_ShootBoltQueueApplier {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 等待部件就绪后预热的载具（join 登记、tick 排空）。 */
    private static final Set<AbstractVehicle> PENDING =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    /**
     * [RVP] 已应用记录：载具 → (武器站 id → 应用时的口径 + 队列表版本号)。
     * {@link #ensureApplied} 据此判断是否需要重写：口径变化（切弹种）或表版本变化
     * （数据重载 / S2C 整表同步）任一发生即重放，否则零开销直接返回。
     */
    private static final Map<AbstractVehicle, Map<String, Applied>> APPLIED = new ConcurrentHashMap<>();
    /**
     * [RVP] 曾查表未命中的载具 → 站 id 集合。
     * 目的：预热/确保时队列表尚未就绪（数据同步晚于载具生成等），进入本集合按
     * {@link #RETRY_INTERVAL_TICKS} 节流重查，命中即应用并移出。
     */
    private static final Map<AbstractVehicle, Set<String>> MISS_RETRY = new ConcurrentHashMap<>();
    /** [RVP] 配置缓存未就绪时的有限重试上限（tick，双端各计各的）：超过后按"确实无配置"放行。 */
    private static final int CONFIG_WAIT_LIMIT_TICKS = 200;
    /** [RVP] miss 重试的节流间隔（tick）。 */
    private static final int RETRY_INTERVAL_TICKS = 10;
    /** [RVP] PENDING 载具的配置等待计数（vehicle → 已重试 tick 数），成功/离开时清除。 */
    private static final Map<AbstractVehicle, Integer> CONFIG_WAIT = new ConcurrentHashMap<>();
    /** [RVP] miss 重试节流计数器（onLevelTick 自增）。 */
    private static int retryTickCounter;

    /** [RVP] 单站的已应用记录：武器口径 + 应用时的队列表版本号。 */
    private record Applied(String weaponKey, long tableGeneration) {}

    private RVP_ShootBoltQueueApplier() {}

    /**
     * [RVP] 确保武器站实例的出弹点为"当前武器口径 × 当前队列表版本"的自定义队列。
     *
     * <p>幂等：口径与表版本均未变化时直接返回（零开销）。变化（切弹种 / 数据重载 /
     * S2C 整表同步）则重查队列表并整体覆盖 {@code station.getBolts()} +
     * {@code countFire(0)} 归位轮转索引。</p>
     *
     * <p>必须在"读取出弹点之前"调用（玩家开火、炮手 AI 开火、干扰物/APS 出生点等）。
     * 查表未命中（队列表尚未就绪）时保持现 bolts 不动，并登记节流重试，就绪后自动补应用。</p>
     *
     * @param vehicle 载具实体（双端各自实例）
     * @param station 武器站（出弹点挂在其 Bolts 列表上）
     */
    public static void ensureApplied(AbstractVehicle vehicle, WeaponUnit station) {
        if (vehicle == null || station == null || vehicle.isRemoved()) {
            return;
        }
        ResourceLocation vehicleId = vehicle.getVehicleId();
        if (vehicleId == null) {
            // 部件数据尚未就绪（initData 未完成），本次保持现状，join 预热/重试机制稍后补应用
            return;
        }
        Map<String, Applied> applied = APPLIED.computeIfAbsent(vehicle, key -> new ConcurrentHashMap<>());
        Applied record = applied.get(station.getId());
        String weaponKey = RVP_ShootBoltQueueResolver.currentWeaponKey(station);
        long generation = RVP_ShootBoltQueueResolver.getTableGeneration();
        if (record != null && record.tableGeneration() == generation && record.weaponKey().equals(weaponKey)) {
            // 目的：口径与表版本均未变化，零开销直接返回
            return;
        }
        // 目的：出弹队列查表（重载期预计算 + S2C 同步），本站未命中沿母武器站链回退
        List<Bolt> queue = RVP_ShootBoltQueueResolver.lookupQueue(vehicleId, station, weaponKey);
        if (queue == null) {
            // 目的：查表未命中——登记节流重试，队列表就绪后自动补应用，不刷屏
            MISS_RETRY.computeIfAbsent(vehicle, key -> ConcurrentHashMap.newKeySet())
                    .add(station.getId());
            return;
        }
        // 目的：公共活引用整体替换出弹点（本体 Ztz99a/Z10 同款模式，不触碰数据模板）
        List<Bolt> bolts = station.getBolts();
        bolts.clear();
        bolts.addAll(queue);
        // 目的：队列长度变化后，轮转索引可能超出新范围，countFire(0) 做一次
        // 纯索引取模归位（不改弹药、不改状态），防止 getCurrentBolt 越界
        station.countFire(0);
        // 目的：记录已应用状态并脱离重试集合
        applied.put(station.getId(), new Applied(weaponKey, generation));
        Set<String> stations = MISS_RETRY.get(vehicle);
        if (stations != null) {
            stations.remove(station.getId());
        }
        // 目的：首次应用输出一行 INFO（载具/站/口径/队列规模/首 Bolt 偏移与管长），
        // 便于确认新队列已生效与今后排查；换口径/换表版本的重放不重复输出
        if (record == null) {
            Bolt first = queue.get(0);
            LOGGER.info("[RVP] 出弹队列已应用 vehicle={} station={} key={} bolts={} first=({},{},{}) 管长={}",
                    vehicleId, station.getId(), weaponKey, queue.size(),
                    String.format(java.util.Locale.ROOT, "%.3f", first.offset.x),
                    String.format(java.util.Locale.ROOT, "%.3f", first.offset.y),
                    String.format(java.util.Locale.ROOT, "%.3f", first.offset.z),
                    String.format(java.util.Locale.ROOT, "%.3f", first.barrelLength));
        }
    }

    /** 目的：载具加入世界时登记预热（双端）；此时部件可能尚未构建，应用推迟到 tick。 */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof AbstractVehicle vehicle) {
            PENDING.add(vehicle);
        }
    }

    /** 目的：载具离开世界（卸载/死亡/跨维度）时清理全部跟踪表，防止实体引用滞留。 */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof AbstractVehicle vehicle) {
            PENDING.remove(vehicle);
            APPLIED.remove(vehicle);
            MISS_RETRY.remove(vehicle);
            CONFIG_WAIT.remove(vehicle);
        }
    }

    /**
     * 目的：tick 中排空预热集合、并对 miss 重试集合做节流重查。
     * 仅在 Phase.END 处理一次；双端各自的 Level 都会触发，逻辑幂等。
     */
    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (PENDING.isEmpty() && APPLIED.isEmpty() && MISS_RETRY.isEmpty()) {
            return;
        }
        // 目的：快照遍历 + 边遍历边摘除（ConcurrentHashMap 迭代器弱一致，安全）
        for (AbstractVehicle vehicle : PENDING) {
            if (tryPrewarmVehicle(vehicle)) {
                PENDING.remove(vehicle);
                CONFIG_WAIT.remove(vehicle);
            }
        }
        retryMissQueues();
    }

    /**
     * 预热：载具部件/配置就绪后，对本站执行一次 {@link #ensureApplied}。
     *
     * @return true = 本载具预热处理完毕（已应用或确认无需应用），可移出挂起集合；
     *         false = 部件/数据未就绪，下 tick 重试
     */
    private static boolean tryPrewarmVehicle(AbstractVehicle vehicle) {
        if (vehicle.isRemoved()) {
            return true;
        }
        ResourceLocation vehicleId = vehicle.getVehicleId();
        if (vehicleId == null) {
            // initData() 尚未执行（部件未构建），下 tick 重试
            return false;
        }
        // 目的：部件列表为空说明 initData 尚未完成，本轮无站可应用，下 tick 重试
        //（EntityJoinLevelEvent 早于 onAddedToWorld/initData，join 当轮 partUnits=0 属正常）
        if (vehicle.getPartUnits().isEmpty()) {
            return false;
        }
        // 目的：读取本载具的挂架条目。
        // [RVP] 配置缓存尚未就绪（联机 S2CVehicleRvpConfig 晚于载具 join 等）时有限重试，
        // 超过上限按"确实无配置"放行（保持未配置载具零影响）；即便放行，
        // 开火口的 ensureApplied 仍会在配置就绪后补应用。
        List<RVP_CustomMountConfig> configs = RVP_CustomMountConfigCache.get(vehicleId);
        if (configs.isEmpty()) {
            int attempts = CONFIG_WAIT.merge(vehicle, 1, Integer::sum);
            return attempts > CONFIG_WAIT_LIMIT_TICKS;
        }
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (!(partUnit instanceof WeaponUnit station)) {
                continue;
            }
            if (!hasShootStructureBones(configs, station)) {
                continue;
            }
            ensureApplied(vehicle, station);
        }
        return true;
    }

    /** 目的：预热时查表未命中的站按节流间隔重查——队列表就绪后自动补预热。 */
    private static void retryMissQueues() {
        if (MISS_RETRY.isEmpty()) {
            return;
        }
        // 目的：节流——重查仅每 RETRY_INTERVAL_TICKS 执行一次（队列表只在重载/同步时变化）
        if (++retryTickCounter % RETRY_INTERVAL_TICKS != 0) {
            return;
        }
        for (Map.Entry<AbstractVehicle, Set<String>> entry : MISS_RETRY.entrySet()) {
            AbstractVehicle vehicle = entry.getKey();
            if (vehicle.isRemoved()) {
                MISS_RETRY.remove(vehicle);
                continue;
            }
            for (String stationId : entry.getValue()) {
                // 目的：按部件 id 取回武器站实例（不在 partUnits 列表中的站已失效，跳过）
                if (vehicle.getPartUnit(stationId).orElse(null) instanceof WeaponUnit station) {
                    ensureApplied(vehicle, station);
                }
            }
        }
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
