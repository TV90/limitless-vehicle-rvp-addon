package org.ywzj.rvp.guidance.runtime;

import net.minecraft.world.entity.Entity;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceIntent;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeContext;
import org.ywzj.rvp.guidance.RVP_GuidanceTargetUtil;
import org.ywzj.rvp.guidance.RVP_RuntimeGuidanceSource;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.runtime.RVP_RuntimeSeekerSupport;
import org.ywzj.rvp.radar.RVP_RadarRoleHelper;
import org.ywzj.rvp.weapon.core.RVP_WeaponLockStateTable;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import javax.annotation.Nullable;

/**
 * SARH 半主动雷达制导源。
 *
 * <p>制导条件：玩家通过锁定键手动锁定了目标，或通过外置雷达请求锁定了目标。
 * 雷达的 TWS 自动跟踪锁不作为 SARH 的制导来源（防止"雷达扫到就制导"的过度行为）。
 * 玩家主动锁定的目标写入武器站的 {@code lockedEntity}，SARH 由此获取照射目标。</p>
 */
public final class RVP_RuntimeSarhGuidanceSource implements RVP_RuntimeGuidanceSource {

    public RVP_RuntimeSarhGuidanceSource() {}

    @Override
    public RVP_EnumGuidanceType type() {
        return RVP_EnumGuidanceType.SARH;
    }

    @Override
    public RVP_GuidanceIntent evaluate(RVP_GuidanceRuntimeContext context) {
        // 主动ECM干扰：阻断半主动雷达照射（SARH 需照射源，干扰期视为无照射）
        if (context.projectile().ecmActiveJamRemainTick > 0) {
            context.projectile().clearTarget();
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.SARH);
        }

        // 目的：获取照射目标——仅从"手动锁定"来源取，排除雷达 TWS 自动跟踪
        Entity illuminated = getManualIlluminatedTarget(context.projectile());
        if (illuminated == null || !illuminated.isAlive()) {
            context.projectile().clearTarget();
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.SARH);
        }

        context.projectile().setTargetEntity(illuminated);
        Entity target = org.ywzj.rvp.guidance.runtime.RVP_RuntimeSeekerSupport.validateEntity(
                context.projectile(), illuminated, RVP_EnumGuidanceType.SARH, context.active());
        if (target == null) {
            return RVP_GuidanceIntent.failed(RVP_EnumGuidanceType.SARH);
        }
        return RVP_GuidanceIntent.entity(target, false, 1.0, RVP_EnumGuidanceType.SARH);
    }

    /**
     * 获取"手动锁定"的照射目标：取雷达锁定（玩家按锁定键写入 RadarUnit.lockedEntity）+
     * 外置雷达锁（外置雷达控制器写入）。
     *
     * <p>不用 {@code WeaponUnit.lockedEntity} 作为来源：它会被 ARH/IR 导引头自动锁定污染
     * （本体 {@code WeaponUnit.tickFireControl} 的 {@code Radar.findTarget} 自动锁定写的是
     * WeaponUnit.lockedEntity，雷达并未锁定），也会被切换武器清空（本体
     * {@code setCurrentWeaponIndex} → {@code setLockedEntity(null)}）。这两者正是
     * “切主动弹开导引头引导半主动弹”与“切武器导致半主动弹脱锁”两个 bug 的根源。</p>
     */
    @Nullable
    private static Entity getManualIlluminatedTarget(RVP_BaseBullet projectile) {
        WeaponUnit unit = projectile.getShooterWeaponUnit();
        if (unit == null) {
            return null;
        }
        WeaponUnit root = unit.getRootParentWeaponUnit();

        // 来源 1：雷达锁定（玩家手动锁定经 fireControlLock / applyRequestedLock 写入 RadarUnit）
        RadarUnit radar = RVP_RadarRoleHelper.getLockedRadar(root);
        if (radar != null) {
            Entity radarLocked = radar.getLockedEntity();
            if (radarLocked != null && radarLocked.isAlive()) {
                return radarLocked;
            }
        }

        // 来源 2：外置雷达锁（外置雷达控制器 / AI 枪手写入）
        if (root != null) {
            int extId = RVP_WeaponLockStateTable.getExternalRadarLockedEntityId(root);
            if (extId != Integer.MIN_VALUE) {
                Entity ext = projectile.level().getEntity(extId);
                if (ext != null && ext.isAlive()) {
                    return ext;
                }
            }
        }
        return null;
    }
}
