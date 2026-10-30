package org.ywzj.rvp.weapon.visual;

import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.light.RVP_LightParameters;
import org.ywzj.rvp.util.RVP_WeaponResolveHelper;
import org.ywzj.rvp.weapon.core.RVP_AimContexts;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.weapon.data.VehicleCannonWeaponData;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;
import java.util.ArrayList;
import java.util.List;

/** 双端共用的实际武器、口径和炮口查询；烟雾与灯光共用，无具体武器 ID 分支。 */
public final class RVP_FiringMuzzleResolver {
    private RVP_FiringMuzzleResolver() { }

    /** @param muzzleIndex 武器站内稳定炮口索引 @param aimContext 本体真实开火上下文 */
    public record MuzzleContext(int muzzleIndex, AimContext aimContext) {
        /** 读取真实炮口，保留本体上下文回退语义。 */
        public Vec3 position() {
            // 调用本项目统一炮口出口，烟和光不会各自猜测发射位置。
            return RVP_AimContexts.muzzle(aimContext);
        }
    }

    /** 按实际齐射/轮射模式保留真实炮口，不让轮射点亮全部炮口。 */
    public static List<MuzzleContext> resolveFiringMuzzleContexts(WeaponUnit unit) {
        // 调用本体武器站接口，空站和未初始化模式不生成任何视觉。
        if (unit == null || unit.getFiringMode() == null) { return List.of(); }
        return switch (unit.getFiringMode()) {
            case RIPPLE -> {
                // 调用本体当前炮闩与炮口接口，保持既有炮口烟轮射语义。
                List<?> bolts = unit.getBolts();
                if (bolts == null || bolts.isEmpty()) { yield List.of(); }
                int index = Math.max(0, bolts.indexOf(unit.getCurrentBolt()));
                AimContext aim = unit.aimContext();
                yield aim == null ? List.of() : List.of(new MuzzleContext(index, aim));
            }
            case SALVO, FULL_SALVO -> {
                // 调用本体多炮口接口，齐射的每根炮管分别参与空间合并。
                List<AimContext> aims = unit.aimContexts();
                if (aims == null) { yield List.of(); }
                List<MuzzleContext> result = new ArrayList<>();
                for (int i = 0; i < aims.size(); i++) {
                    if (aims.get(i) != null) { result.add(new MuzzleContext(i, aims.get(i))); }
                }
                yield result;
            }
        };
    }

    /** 解包后按类型化数据读口径；不适用的武器返回 NaN。 */
    public static float resolveCaliber(AbstractVehicleWeapon<?> wrapped, boolean smokeOnly) {
        // 调用代理解包器，兼容多武器站和武器代理。
        AbstractVehicleWeapon<?> weapon = RVP_WeaponResolveHelper.unwrap(wrapped);
        if (weapon == null) { return Float.NaN; }
        // 调用本体数据访问器，用类型区分炮弹与导弹，不检查武器名字。
        Object data = weapon.getData();
        Float caliber;
        if (data instanceof RVP_WeaponData rvp) {
            // 调用 RVP 分类与口径出口，炮口烟仍只接受机枪/机炮类。
            if (smokeOnly && rvp.getWeaponKind() != RVP_EnumWeaponKind.MACHINEGUN) { return Float.NaN; }
            caliber = rvp.getEffectsData().getCaliber();
        } else if (data instanceof VehicleCannonWeaponData cannon) {
            // 调用本体火炮口径，保持非 RVP 火炮兼容。
            caliber = cannon.getCaliber();
        } else { return Float.NaN; }
        return caliber != null && Float.isFinite(caliber) && caliber > 0 ? caliber : 7.62F;
    }

    /** 返回成功开火时的光配置；炸弹、鱼雷、激光与吊舱没有炮口火焰。 */
    public static RVP_LightParameters lightParameters(AbstractVehicleWeapon<?> wrapped) {
        // 调用公共解包器，服务端真实光与客户端屏幕光使用同一弹种。
        AbstractVehicleWeapon<?> weapon = RVP_WeaponResolveHelper.unwrap(wrapped);
        if (weapon == null) { return null; }
        Object data = weapon.getData();
        if (data instanceof RVP_WeaponData rvp) {
            // 调用类型化武器类别，排除投放武器和无火药的设备。
            RVP_EnumWeaponKind kind = rvp.getWeaponKind();
            if (kind != RVP_EnumWeaponKind.MACHINEGUN && kind != RVP_EnumWeaponKind.MISSILE
                    && kind != RVP_EnumWeaponKind.ROCKET) { return null; }
            // 调用代码中的统一炮口光参数，只用既有口径和武器类别计算，不读取 JSON 光源对象。
            return RVP_LightParameters.muzzle(resolveCaliber(weapon, false),
                    kind != RVP_EnumWeaponKind.MACHINEGUN);
        }
        if (data instanceof VehicleCannonWeaponData) {
            // 调用同一口径映射，兼容本体未迁移火炮。
            return RVP_LightParameters.muzzle(resolveCaliber(weapon, false), false);
        }
        return null;
    }
}
