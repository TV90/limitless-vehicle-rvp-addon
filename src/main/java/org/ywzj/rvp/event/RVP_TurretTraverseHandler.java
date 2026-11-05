package org.ywzj.rvp.event;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.api.event.VehicleMoveEvent;
import org.ywzj.vehicle.api.event.VehicleFireEvent;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** 用本体武器站的公开接口限制水平方向机，独立于炮管俯仰和车辆动力。 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID)
public final class RVP_TurretTraverseHandler {
    /** 两端各自记录受限站 ID，便于在维修或绑定改动后恢复原速。 */
    private static final Map<AbstractVehicle, Set<String>> SERVER_LIMITED = new WeakHashMap<>();
    private static final Map<AbstractVehicle, Set<String>> CLIENT_LIMITED = new WeakHashMap<>();

    private RVP_TurretTraverseHandler() {}

    @SubscribeEvent(receiveCanceled = true)
    public static void onVehicleMove(VehicleMoveEvent event) {
        AbstractVehicle vehicle = event.getVehicle();
        if (!vehicle.level().isClientSide() && !vehicle.isRemoved()) {
            // 本体此事件先于武器站旋转，每刻从基础配置重算，避免倍率累乘。
            apply(vehicle, RVP_BoneModuleStateTable.getInactiveModules(vehicle.getUUID()));
        }
    }

    public static void apply(AbstractVehicle vehicle, Map<String, Set<BoneModuleType>> inactive) {
        var limited = vehicle.level().isClientSide() ? CLIENT_LIMITED : SERVER_LIMITED;
        Set<String> previous = limited.getOrDefault(vehicle, Set.of());
        if (previous.isEmpty() && inactive.isEmpty()) return;
        Map<String, Integer> stages = new HashMap<>();
        inactive.forEach((bone, types) -> {
            int stage = types.contains(BoneModuleType.TURRET_TRAVERSE) ? 2
                    : types.contains(BoneModuleType.TURRET_TRAVERSE_DAMAGED) ? 1 : 0;
            if (stage == 0) return;
            var binding = RVP_VehicleHitboxFactorManager.INSTANCE.resolveTurretTraverseConfig(vehicle, bone);
            if (binding != null) stages.merge(binding.partId(), stage, Math::max);
        });
        for (String partId : previous) {
            if (!stages.containsKey(partId)) setSpeed(vehicle, partId, 0);
        }
        stages.forEach((partId, stage) -> setSpeed(vehicle, partId, stage));
        if (stages.isEmpty()) limited.remove(vehicle);
        else limited.put(vehicle, Set.copyOf(stages.keySet()));
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && !event.level.isClientSide()) {
            constrainLevel(event.level);
        }
    }

    /** 兼顾没有 RVP 武器的受损武器站，并在角度修正后同步碰撞盒。 */
    public static void constrainLevel(Level level) {
        if (level == null) return;
        var limited = level.isClientSide() ? CLIENT_LIMITED : SERVER_LIMITED;
        limited.forEach((vehicle, partIds) -> {
            if (vehicle.level() != level || vehicle.isRemoved()) return;
            constrainVehicle(vehicle, partIds);
        });
    }

    /** RVP 武器读取姿态前校正本车受损站；包含承载该武器的父炮塔，不限制独立站自身的旋转。 */
    public static void constrain(WeaponUnit unit) {
        if (unit == null) return;
        AbstractVehicle vehicle = unit.getVehicle();
        var limited = vehicle.level().isClientSide() ? CLIENT_LIMITED : SERVER_LIMITED;
        Set<String> parts = limited.get(vehicle);
        if (parts != null) constrainVehicle(vehicle, parts);
    }

    /** 同步实际结构和碰撞盒，避免修正炮塔后瞄准射线仍撞到修正前的 OBB。 */
    private static void constrainVehicle(AbstractVehicle vehicle, Set<String> partIds) {
        boolean corrected = false;
        for (String partId : partIds) {
            if (vehicle.getPartUnit(partId).orElse(null) instanceof WeaponUnit unit) {
                corrected |= constrainYaw(unit);
            }
        }
        if (corrected) vehicle.updateOBBs();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeFire(VehicleFireEvent.Pre event) {
        // 本体在 Pre 之后采集客户端发射上下文；多弹种、原生武器及代理都必须先看到受限姿态。
        constrain(event.getWeapon().getWeaponUnit());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeFireEffects(VehicleFireEvent.Post event) {
        // 成功回包通过同一 Post 通知炮口火光、烟和声音；在各视觉监听器之前修正。
        constrain(event.getWeapon().getWeaponUnit());
    }

    /** 只查询当前站自身的损伤限制，避免其他炮塔/车长独立瞄具被连带锁定。 */
    public static boolean isLimited(WeaponUnit unit) {
        var limited = unit.getVehicle().level().isClientSide() ? CLIENT_LIMITED : SERVER_LIMITED;
        return limited.getOrDefault(unit.getVehicle(), Set.of()).contains(unit.getId());
    }

    /** 受损观瞄的实际姿态，复用本体基座/炮口偏置，并与渲染相同地插值。 */
    public static org.joml.Quaternionf sightRotation(WeaponUnit unit, float partial) {
        // 调用共用基座采样，吊舱规划与模型渲染沿同一父级姿态取样。
        var rotation = sightBaseRotation(unit, partial);
        rotation.rotateY((float) Math.toRadians(-unit.getViewYRot(partial) - unit.yBarrelSelfRot));
        rotation.rotateX((float) Math.toRadians(unit.getViewXRot(partial) + unit.xBarrelSelfRot));
        return rotation;
    }

    /** 只读取观瞄基座，不包含本站可动两轴；供双端吊舱运动规划使用。 */
    public static org.joml.Quaternionf sightBaseRotation(WeaponUnit unit, float partial) {
        // 基座沿用本体公开变换，再将车体旋转替换为当前渲染插值；父站的转角也逐级插值。
        var vehicle = unit.getVehicle();
        org.joml.Quaternionf rotation = vehicle.rotYXZ(partial);
        var group = unit.getStructureGroup() != null ? unit.getStructureGroup() : unit.xTurnGroup;
        if (group != null) {
            rotation.mul(group.baseRotation);
            if (group.parent != null) rotation.mul(group.parent.globalTransform(
                    net.minecraft.world.phys.Vec3.ZERO, false,
                    g -> g.rotationO == null ? new org.joml.Quaternionf(g.rotation)
                            : new org.joml.Quaternionf(g.rotationO).slerp(g.rotation, partial), g -> g.offset).rotation());
        }
        return rotation;
    }

    private static boolean constrainYaw(WeaponUnit unit) {
        float speed = unit.getYRotSpeed();
        if (!Float.isFinite(speed) || speed < 0f) return false;
        float delta = Mth.wrapDegrees(unit.getYRot() - unit.yRotO);
        float bounded = Mth.clamp(delta, -speed, speed);
        if (Math.abs(delta - bounded) <= 1.0e-5f) return false;

        float yaw = Mth.wrapDegrees(unit.yRotO + bounded);
        unit.yRotO = yaw - bounded;
        unit.setYRot(yaw);
        var yawGroup = unit.getStructureGroup();
        var pitchGroup = unit.xTurnGroup;
        var previousYaw = yawGroup == null ? null : yawGroup.rotationO;
        var previousPitch = pitchGroup == null ? null : pitchGroup.rotationO;
        unit.updateRot();
        // updateRot 已在本体执行过一次，保留它为渲染保存的上一刻姿态。
        if (yawGroup != null) yawGroup.rotationO = previousYaw;
        if (pitchGroup != null) pitchGroup.rotationO = previousPitch;
        return true;
    }

    private static void setSpeed(AbstractVehicle vehicle, String partId, int stage) {
        // 通过部件 ID 取本车这一座炮塔，使用其配置基准而非上一刻已经受限的值。
        if (vehicle.getPartUnit(partId).orElse(null) instanceof WeaponUnit unit) {
            float nominal = unit.getData().getRotInfo().yRotSpeed;
            if (Float.isFinite(nominal)) unit.setYRotSpeed(speedForStage(nominal, stage));
        }
    }

    static float speedForStage(float nominal, int stage) {
        if (nominal <= 0f) return nominal;
        return switch (stage) {
            case 1 -> nominal * 0.3f;
            case 2 -> 0.01f;
            default -> nominal;
        };
    }
}
