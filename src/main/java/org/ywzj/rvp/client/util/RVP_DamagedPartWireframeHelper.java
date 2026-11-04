package org.ywzj.rvp.client.util;

import org.ywzj.rvp.client.state.RVP_ClientBoneModuleState;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** HUD、状态 AUI 和调试 OBB 共用的模块损伤配色；原生 PartUnit 血量不参与模块档位。 */
public final class RVP_DamagedPartWireframeHelper {
    /** 一级受损填充：半透明橙色；边线由本体转为同色不透明。 */
    public static final int DAMAGED_FILL = 0x80FF9900;
    /** 二级损坏或单档模块失效填充：半透明红色。 */
    public static final int DESTROYED_FILL = 0x80FF0000;
    /** 真实骨组与 OBB 的静态映射，载具/模型重建时刷新。 */
    private static final RVP_ScopeCubePartResolver CUBES = new RVP_ScopeCubePartResolver();
    /** AUI 显式传入的绘制载具；嵌套绘制结束恢复，避免读成另一台本地载具。 */
    private static AbstractVehicle displayedVehicle;

    private RVP_DamagedPartWireframeHelper() { }

    public static AbstractVehicle displayedVehicle() {
        return displayedVehicle != null ? displayedVehicle
                : LocalVehiclePlayer.instance == null ? null : LocalVehiclePlayer.instance.vehicle;
    }

    /** 原生俯视图没有公开入口，AUI 调用期间显式传递同一台载具上下文。 */
    public static Scope displaying(AbstractVehicle vehicle) {
        Scope scope = new Scope(displayedVehicle);
        displayedVehicle = vehicle;
        return scope;
    }

    public static final class Scope implements AutoCloseable {
        /** 嵌套绘制前的载具。 */
        private final AbstractVehicle previous;
        private Scope(AbstractVehicle previous) { this.previous = previous; }
        @Override public void close() { displayedVehicle = previous; }
    }

    /** 有模块配置就替换整车原生血量染色；没有模块配置则原样沿用本体。 */
    public static int headingFillColor(@Nullable AbstractVehicle vehicle, VehicleCubeOBB cube, int nativeFill) {
        Map<String, Set<BoneModuleType>> modules = RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneModules(vehicle);
        return chooseFill(!modules.isEmpty(), cubeStage(vehicle, cube, modules), nativeFill);
    }

    /** 纯配色规则，供 HUD 与回归测试共用。正常模块保持白色空心框。 */
    public static int chooseFill(boolean hasModules, int stage, int nativeFill) {
        if (!hasModules) return nativeFill;
        return stage >= 2 ? DESTROYED_FILL : stage == 1 ? DAMAGED_FILL : 0;
    }

    /** 配置骨上最高损伤档位；一档标记不要求在配置的 modules 中重复声明。 */
    public static int stageFor(Set<BoneModuleType> configured, Predicate<BoneModuleType> active) {
        int stage = 0;
        for (BoneModuleType type : configured) {
            boolean lightMarker = type == BoneModuleType.ENGINE_DAMAGED || type == BoneModuleType.BARREL_DAMAGED
                    || type == BoneModuleType.TURRET_TRAVERSE_DAMAGED;
            if (!active.test(type)) stage = Math.max(stage, lightMarker ? 1 : 2);
            BoneModuleType marker = switch (type) {
                case ENGINE -> BoneModuleType.ENGINE_DAMAGED;
                case BARREL -> BoneModuleType.BARREL_DAMAGED;
                case TURRET_TRAVERSE -> BoneModuleType.TURRET_TRAVERSE_DAMAGED;
                default -> null;
            };
            if (marker != null && !active.test(marker)) stage = Math.max(stage, 1);
        }
        return stage;
    }

    public static boolean hasDamagedBone(AbstractVehicle vehicle) {
        for (var entry : RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneModules(vehicle).entrySet()) {
            if (boneStage(vehicle.getId(), entry.getKey(), entry.getValue()) > 0) return true;
        }
        return false;
    }

    public static int cubeStage(AbstractVehicle vehicle, VehicleCubeOBB cube) {
        return cubeStage(vehicle, cube, RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneModules(vehicle));
    }

    private static int cubeStage(@Nullable AbstractVehicle vehicle, VehicleCubeOBB cube,
                                 Map<String, Set<BoneModuleType>> modules) {
        if (vehicle == null || cube == null || modules.isEmpty()) return 0;
        PartUnit<?> part = CUBES.resolve(vehicle, cube);
        String bone = CUBES.resolveBone(vehicle, cube);
        if (bone != null && modules.containsKey(bone)) return boneStage(vehicle.getId(), bone, modules.get(bone));
        if (part == null) return 0;
        String structure = part.getData() == null ? null : part.getData().getStructureBone();
        // 已解析到独立具名子骨时不继承父部件损伤，尤其不能把炮管档位染到整个炮塔。
        if (bone != null && !bone.equals(structure)) return 0;
        if (part instanceof WeaponUnit weapon && structure != null
                && RVP_VehicleHitboxFactorManager.isBarrelGroupCube(cube, weapon)
                && modules.containsKey(structure + "_barrel")) {
            String barrel = structure + "_barrel";
            return boneStage(vehicle.getId(), barrel, modules.get(barrel));
        }
        String configuredBone = structure != null && modules.containsKey(structure) ? structure : part.getId();
        return boneStage(vehicle.getId(), configuredBone, modules.getOrDefault(configuredBone, Set.of()));
    }

    private static int boneStage(int entityId, String bone, Set<BoneModuleType> configured) {
        // 调用现有服务端同步侧表；维修和第二档升级均随同一份权威状态立即变色。
        return stageFor(configured, type -> RVP_ClientBoneModuleState.isModuleActive(entityId, bone, type));
    }
}
