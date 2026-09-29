package org.ywzj.rvp.client.util;

import org.ywzj.rvp.client.state.RVP_ClientBoneModuleState;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.structure.VehicleCubeOBB;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;

/**
 * 部件调试线框变色判定（2026-09-29 用户需求，纯客户端）：
 * 观瞄俯视图（X 键开镜）与 F3+B/O 键 OBB 线框中，**失效模块骨**（爆反/设备/炮管，即
 * {@code bone_modules} 配置骨上任意模块失效）的 cube/线框画成近黑色（{@link #DAMAGED_RGB}），
 * 与本体既有配色（车体绿/物理蓝/武器红/部件黄/饰品品红）区分。
 *
 * <p>判定口径与面板/俯视图一致：骨名（部件 id，模块骨名==部件 id 的 RVP 约定；炮管组
 * cube 按炮管骨 {@code structure_bone+"_barrel"}）→ {@link RVP_ClientBoneModuleState#isModuleActive}
 * （S2C 失效侧表，entityId 键）。近黑 RGB 26 在 RenderType.lines（无光照）下呈暗灰黑，
 * 3D 世界里仍可辨识轮廓、又明显区别于本体原色。</p>
 */
public final class RVP_DamagedPartWireframeHelper {

    /** 失效部件线框色（近黑，RGB 26/26/26，用户定版）。 */
    public static final float DAMAGED_RGB = 26f / 255f;

    private RVP_DamagedPartWireframeHelper() {
    }

    /** 载具是否存在失效模块骨（快速门控：无失效时不接管绘制，透传本体原路径）。 */
    public static boolean hasDestroyedBone(AbstractVehicle vehicle) {
        Map<String, Set<BoneModuleType>> modules =
                org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneModules(vehicle);
        if (modules == null || modules.isEmpty()) {
            return false;
        }
        int entityId = vehicle.getId();
        for (Map.Entry<String, Set<BoneModuleType>> entry : modules.entrySet()) {
            for (BoneModuleType type : entry.getValue()) {
                if (!RVP_ClientBoneModuleState.isModuleActive(entityId, entry.getKey(), type)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 单 cube 是否属于失效模块骨：cube → 所属部件（partUnit）→ 骨名（部件 id，模块骨名
     * ==部件 id 的 RVP 约定；炮管组 cube 按炮管骨 {@code structure_bone+"_barrel"}）→
     * 查失效侧表。
     *
     * @param vehicle  cube 所属载具（判定数据源）
     * @param partUnit cube 所属部件（俯视图/线框循环天然持有），可空
     * @param cube     待判定的 cube
     */
    public static boolean isDamagedPartCube(AbstractVehicle vehicle, @Nullable PartUnit<?> partUnit, VehicleCubeOBB cube) {
        if (vehicle == null || partUnit == null || cube == null) {
            return false;
        }
        // 部件自身骨（模块骨名 == 部件 id，RVP 既有约定）
        if (isBoneDamaged(vehicle, partUnit.getId())) {
            return true;
        }
        // 武器站炮管组 cube：按炮管骨（structure_bone + "_barrel"）判（§31.2.6 同款拆分）
        if (partUnit instanceof WeaponUnit weaponUnit
                && org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager.isBarrelGroupCube(cube, weaponUnit)) {
            String structureBone = weaponUnit.getData() == null ? null : weaponUnit.getData().getStructureBone();
            return structureBone != null && !structureBone.isBlank()
                    && isBoneDamaged(vehicle, structureBone + "_barrel");
        }
        return false;
    }

    /** 骨上任意模块失效：配置骨任一模块失效即 true；非模块骨/未配置 false（不变色）。 */
    public static boolean isBoneDamaged(AbstractVehicle vehicle, String boneName) {
        if (boneName == null || boneName.isBlank()) {
            return false;
        }
        Map<String, Set<BoneModuleType>> modules =
                org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneModules(vehicle);
        Set<BoneModuleType> types = modules == null ? null : modules.get(boneName);
        if (types == null) {
            return false;
        }
        int entityId = vehicle.getId();
        for (BoneModuleType type : types) {
            if (!RVP_ClientBoneModuleState.isModuleActive(entityId, boneName, type)) {
                return true;
            }
        }
        return false;
    }
}
