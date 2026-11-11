package org.ywzj.rvp.mount;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.debug.RVP_DebugFlags;
import org.ywzj.rvp.util.RVP_ServerVehicleIndex;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.Bolt;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * [RVP] 自定义挂架出弹队列的按武器切换（双端）：
 * 数据层只写整站并集，这里在运行期把实体 {@code WeaponUnit.bolts} 活列表切换为
 * <b>当前武器自己的出弹队列</b>——同一武器站内不同弹种可各自拥有不同数量/顺序的出弹点
 * （如 F/A-18 内侧挂点的两发炸弹只从内侧重挂点出弹，8 发阻尼人则在两个内侧挂点间交替）。
 *
 * <p>切换时机：当前武器索引或队列表版本（{@code /reload}、客户端资源重载）变化后的
 * 下一 tick。客户端开火坐标由客户端优先计算，因此客户端也必须切换；服务端侧供 AI 乘员
 * 与近炸/烟效等按出弹点取位。</p>
 *
 * <p>轮转下标按武器记忆：每把武器有自己的弹药计数与弹体消失进度，切走时保存
 * {@code currentBoltIndex}、切回时恢复，保证"第 k 发从哪个挂点出"与"第 k 枚弹体消失"
 * 始终对齐。不新增 Mixin：遍历沿用既有载具注册表/客户端渲染实体集合，写入走本体公开的
 * {@code getBolts()} 活列表；仅下标读写用反射（与 {@code xTurnGroup} 同款兜底策略）。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID)
public final class RVP_ShootBoltWeaponSync {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 已应用的（队列表版本 + 当前武器），双端各自持有（集成服务端同 JVM 但线程不同）。 */
    private static final Map<WeaponUnit, Applied> SERVER_APPLIED = new WeakHashMap<>();
    private static final Map<WeaponUnit, Applied> CLIENT_APPLIED = new WeakHashMap<>();
    /** 各武器切走时的轮转下标，切回时恢复。 */
    private static final Map<WeaponUnit, Map<ResourceLocation, Integer>> SERVER_SAVED_INDEX = new WeakHashMap<>();
    private static final Map<WeaponUnit, Map<ResourceLocation, Integer>> CLIENT_SAVED_INDEX = new WeakHashMap<>();
    /** {@code WeaponUnit.currentBoltIndex} 反射字段（解析一次复用；失败退化为 countFire(0)）。 */
    private static volatile java.lang.reflect.Field currentBoltIndexField;
    private static volatile boolean currentBoltIndexResolveFailed;

    private record Applied(long version, @Nullable ResourceLocation weaponId) {}

    private RVP_ShootBoltWeaponSync() {}

    /** 服务端每 tick 遍历注册表内载具（客户端世界没有该注册表，由客户端 tick 调用 {@link #tickVehicles}）。 */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            tickVehicles(RVP_ServerVehicleIndex.getVehicles(level));
        }
    }

    /** 对一批实体中的载具应用当前武器的出弹队列（双端通用，迭代开销随载具数量线性）。 */
    public static void tickVehicles(Iterable<? extends Entity> entities) {
        if (entities == null) {
            return;
        }
        for (Entity entity : entities) {
            if (entity instanceof AbstractVehicle vehicle && !vehicle.isRemoved()) {
                applyVehicle(vehicle);
            }
        }
    }

    /** 对单台载具的全部武器站应用当前武器的出弹队列；无自定义出弹骨的站直接跳过。 */
    public static void applyVehicle(AbstractVehicle vehicle) {
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof WeaponUnit unit) {
                applyStation(unit);
            }
        }
    }

    /**
     * 对单个武器站应用当前武器的出弹队列。
     *
     * <p>除每 tick 的批量同步外，客户端开火前也会再调一次（见
     * {@code RVP_WeaponBase.doClientShoot}）：同一帧内先切武器再按开火时，
     * tick 末尾的批量同步尚未跑到，这一步保证开火坐标用的是新武器的队列。</p>
     */
    public static void applyStation(WeaponUnit unit) {
        AbstractVehicle vehicle = unit.getVehicle();
        if (vehicle == null) {
            return;
        }
        ResourceLocation vehicleId = vehicle.getVehicleId();
        if (vehicleId == null) {
            return;
        }
        RVP_ShootBoltQueueResolver.StationBoltQueues queues =
                RVP_ShootBoltQueueResolver.getStationQueues(vehicleId, unit.getId());
        if (queues == null) {
            return;
        }
        boolean clientSide = vehicle.level().isClientSide();
        Map<WeaponUnit, Applied> applied = clientSide ? CLIENT_APPLIED : SERVER_APPLIED;
        Map<WeaponUnit, Map<ResourceLocation, Integer>> savedIndices =
                clientSide ? CLIENT_SAVED_INDEX : SERVER_SAVED_INDEX;
        long version = RVP_ShootBoltQueueResolver.cacheVersion();
        ResourceLocation weaponId = currentWeaponId(unit);
        Applied previous = applied.get(unit);
        if (previous != null && previous.version() == version
                && Objects.equals(previous.weaponId(), weaponId)) {
            return;
        }
        List<Bolt> target = queues.boltsFor(weaponId);
        if (target == null || target.isEmpty()) {
            return;
        }
        List<Bolt> live = unit.getBolts();
        boolean contentChanged = !sameBolts(live, target);
        // 目的：换武器时记住旧武器打到第几个出弹点；同一武器仅队列版本变化（/reload）不重置
        int desiredIndex;
        if (previous != null && !Objects.equals(previous.weaponId(), weaponId)) {
            if (previous.weaponId() != null) {
                savedIndices.computeIfAbsent(unit, key -> new HashMap<>())
                        .put(previous.weaponId(), readBoltIndex(unit));
            }
            Integer restored = weaponId == null ? null : savedIndices.getOrDefault(unit, Map.of()).get(weaponId);
            desiredIndex = restored == null ? 0 : restored;
        } else {
            desiredIndex = previous == null ? 0 : readBoltIndex(unit);
        }
        if (contentChanged) {
            live.clear();
            live.addAll(target);
        }
        int targetIndex = Math.floorMod(desiredIndex, target.size());
        writeBoltIndex(unit, targetIndex);
        if (RVP_DebugFlags.SHOOT_BOLT.isEnabled() && (contentChanged || previous != null)) {
            LOGGER.info("[RVP][出弹] 切换队列 vehicle={} station={} weapon={} bolts={} index={} side={}",
                    vehicleId, unit.getId(), weaponId, target.size(), targetIndex,
                    clientSide ? "client" : "server");
        }
        applied.put(unit, new Applied(version, weaponId));
    }

    @Nullable
    private static ResourceLocation currentWeaponId(WeaponUnit unit) {
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElse(null);
        if (weapon == null || weapon.getData() == null) {
            return null;
        }
        return weapon.getData().getWeaponId();
    }

    private static boolean sameBolts(List<Bolt> live, List<Bolt> target) {
        if (live.size() != target.size()) {
            return false;
        }
        for (int i = 0; i < live.size(); i++) {
            Bolt a = live.get(i);
            Bolt b = target.get(i);
            if (a == b) {
                continue;
            }
            if (a.offset.distanceToSqr(b.offset) > 1.0E-9
                    || Float.compare(a.barrelLength, b.barrelLength) != 0
                    || Float.compare(a.xRot, b.xRot) != 0
                    || Float.compare(a.yRot, b.yRot) != 0) {
                return false;
            }
        }
        return true;
    }

    private static int readBoltIndex(WeaponUnit unit) {
        java.lang.reflect.Field field = resolveBoltIndexField();
        if (field == null) {
            return 0;
        }
        try {
            return field.getInt(unit);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void writeBoltIndex(WeaponUnit unit, int index) {
        java.lang.reflect.Field field = resolveBoltIndexField();
        if (field == null) {
            // 反射不可用：至少把越界下标收敛到新队列长度内
            unit.countFire(0);
            return;
        }
        try {
            field.setInt(unit, index);
        } catch (Throwable t) {
            unit.countFire(0);
        }
    }

    /**
     * 解析并缓存 {@code WeaponUnit.currentBoltIndex}（双重检查 + 失败记忆）。
     * 字段在运行时会被重映射，必须经 {@link ObfuscationReflectionHelper} 按可读名解析。
     */
    @Nullable
    private static java.lang.reflect.Field resolveBoltIndexField() {
        java.lang.reflect.Field cached = currentBoltIndexField;
        if (cached != null) {
            return cached;
        }
        if (currentBoltIndexResolveFailed) {
            return null;
        }
        synchronized (RVP_ShootBoltWeaponSync.class) {
            if (currentBoltIndexField != null) {
                return currentBoltIndexField;
            }
            if (currentBoltIndexResolveFailed) {
                return null;
            }
            try {
                java.lang.reflect.Field field = ObfuscationReflectionHelper.findField(WeaponUnit.class, "currentBoltIndex");
                field.setAccessible(true);
                currentBoltIndexField = field;
                return field;
            } catch (Throwable t) {
                currentBoltIndexResolveFailed = true;
                LOGGER.warn("[RVP] 无法解析 WeaponUnit.currentBoltIndex，换武器时出弹点下标只做取模收敛"
                        + "（后续不再重复告警）", t);
                return null;
            }
        }
    }
}
