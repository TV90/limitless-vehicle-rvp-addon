package org.ywzj.rvp.mount;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_LauncherDeployConfigCache;
import org.ywzj.rvp.debug.RVP_DebugFlags;
import org.ywzj.rvp.util.RVP_ServerVehicleIndex;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.Bolt;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeGroup;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;
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
 * 始终对齐。通过本体炮口索引与轮转接口读写，双端分别保存各武器的进度。</p>
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

    /** 当前挂到实体武器站上的构型受击盒，切换时只移除这一组。 */
    private record Applied(long version, @Nullable ResourceLocation weaponId, List<VehicleCubeOBB> barrelShapes) {}

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
        boolean clientSide = vehicle.level().isClientSide();
        Map<WeaponUnit, Applied> applied = clientSide ? CLIENT_APPLIED : SERVER_APPLIED;
        Map<WeaponUnit, Map<ResourceLocation, Integer>> savedIndices =
                clientSide ? CLIENT_SAVED_INDEX : SERVER_SAVED_INDEX;
        if (queues == null) {
            Applied previous = applied.remove(unit);
            if (previous != null && !previous.barrelShapes().isEmpty()) {
                unit.getPartCubeOBBs().removeAll(previous.barrelShapes());
                // 配置移除后用本体模板恢复炮管序列，避免沿用旧改装的筒口。
                List<Bolt> original = unit.getData().getBolts();
                if (original != null && !original.isEmpty()) {
                    unit.getBolts().clear();
                    unit.getBolts().addAll(original);
                    writeBoltIndex(unit, 0);
                }
            }
            return;
        }
        long version = RVP_ShootBoltQueueResolver.cacheVersion();
        // 调用本体武器站取当前改装子项，使用资源 ID 选队列，同时读取它的实际存弹量。
        Applied previous = applied.get(unit);
        if (!queues.barrelGroupsByWeapon().isEmpty()) {
            applyLauncherStation(unit, queues, selectedLauncherWeapon(unit), version, previous, applied);
            return;
        }
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElse(null);
        ResourceLocation weaponId = weaponId(weapon);
        if (previous != null && !previous.barrelShapes().isEmpty()) {
            unit.getPartCubeOBBs().removeAll(previous.barrelShapes());
        }
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
        applied.put(unit, new Applied(version, weaponId, List.of()));
    }

    /** 多弹种包装先触发本体 Pre，再取炮口；开火当帧按实际武器重新应用构型。 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void beforeLauncherFire(VehicleFireEvent.Pre event) {
        // 调用共用解包入口获取真正要发射的子武器，避免根武器站走其他选项。
        AbstractVehicleWeapon<?> weapon = RVP_WeaponResolveHelper.unwrap(event.getWeapon());
        if (weapon == null) return;
        WeaponUnit unit = weapon.getWeaponUnit();
        ResourceLocation vehicleId = event.getVehicle().getVehicleId();
        var queues = RVP_ShootBoltQueueResolver.getStationQueues(vehicleId, unit.getId());
        if (queues == null || queues.barrelGroupsByWeapon().isEmpty()) {
            // 配置了独立组但结构尚未加载的武器站不使用本体默认炮口。
            if (RVP_LauncherDeployConfigCache.get(vehicleId).stream().anyMatch(config ->
                    !config.barrelBonesByWeapon().isEmpty() && config.pitchPartUnitId().equals(unit.getId()))) {
                event.setCanceled(true);
            }
            return;
        }
        Map<WeaponUnit, Applied> applied = event.isClientSide() ? CLIENT_APPLIED : SERVER_APPLIED;
        if (!applyLauncherStation(unit, queues, weapon, RVP_ShootBoltQueueResolver.cacheVersion(), applied.get(unit), applied)) {
            event.setCanceled(true);
        }
    }

    @Nullable
    private static AbstractVehicleWeapon<?> selectedLauncherWeapon(WeaponUnit unit) {
        for (WeaponUnit seat = unit; seat != null; seat = seat.getParentWeaponUnit()) {
            // 本体把带 part_unit_id 的改装武器挂在席位上，子部件可能只负责结构。
            AbstractVehicleWeapon<?> selected = RVP_WeaponResolveHelper.currentPrimary(seat);
            if (selected != null && selected.getWeaponUnit() == unit) return selected;
        }
        return null;
    }

    @Nullable
    private static ResourceLocation weaponId(@Nullable AbstractVehicleWeapon<?> weapon) {
        return weapon == null || weapon.getData() == null ? null : weapon.getData().getWeaponId();
    }

    private static boolean applyLauncherStation(WeaponUnit unit, RVP_ShootBoltQueueResolver.StationBoltQueues queues,
                                                @Nullable AbstractVehicleWeapon<?> weapon, long version,
                                                @Nullable Applied previous, Map<WeaponUnit, Applied> applied) {
        ResourceLocation weaponId = weaponId(weapon);
        var choice = queues.barrelGroupFor(weaponId);
        VehicleCubeGroup group = choice == null ? null : choice.liveGroup(unit);
        if (group == null) return false;
        List<Bolt> target = choice.bolts();
        if (previous == null || previous.version() != version || !Objects.equals(previous.weaponId(), weaponId)) {
            List<VehicleCubeOBB> shapes = choice.templates().stream().map(template -> {
                // 复制本体解析的盒，绑定选中子骨的实例旋转。
                VehicleCubeOBB shape = new VehicleCubeOBB(template);
                shape.group = group;
                shape.update(unit.getVehicle());
                return shape;
            }).toList();
            if (previous != null) unit.getPartCubeOBBs().removeAll(previous.barrelShapes());
            unit.getPartCubeOBBs().addAll(shapes);
            List<Bolt> live = unit.getBolts();
            if (!sameBolts(live, target)) {
                live.clear();
                live.addAll(target);
            }
            applied.put(unit, new Applied(version, weaponId, shapes));
        }
        // 装满使用首筒；剩一发使用最后一筒，与脚本判定同源。
        int remaining = weapon == null ? target.size() : weapon.getRemainAmmo();
        writeBoltIndex(unit, Math.min(target.size() - 1, Math.max(0, target.size() - remaining)));
        return true;
    }

    private static int readBoltIndex(WeaponUnit unit) {
        List<Bolt> bolts = unit.getBolts();
        if (bolts.isEmpty()) return 0;
        // 调用本体轮转接口将索引收敛至当前队列；每个炮口持有独立的 Bolt。
        unit.countFire(0);
        return bolts.indexOf(unit.getCurrentBolt());
    }

    private static void writeBoltIndex(WeaponUnit unit, int index) {
        int size = unit.getBolts().size();
        if (size > 0) unit.countFire(Math.floorMod(index - readBoltIndex(unit), size));
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

}
