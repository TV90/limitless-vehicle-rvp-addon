package org.ywzj.rvp.client.light;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteAmmoVisualState;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.light.RVP_LightParameters;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;

/** 本地与超视距克隆共用的燃烧事实和模型喷口查询，不依赖是否生成尾焰粒子。 */
public final class RVP_RocketEngineLightQuery {
    private RVP_RocketEngineLightQuery() { }

    /** 本地沿用弹体窗口；克隆只接受未超时的服务端燃烧快照，不猜测点火。 */
    public static boolean isBurning(RVP_BaseBullet bullet) {
        if (bullet.isRemoved()) { return false; }
        // 调用 RVP 弹体客户端安全配置，避免客户端 rvpData 尚未赋值而永久无光。
        RVP_WeaponData data = bullet.getResolvedWeaponConfig();
        if (data == null || !data.getProjectileData().hasRocketEngine()) { return false; }
        // 调用严格快照出口，明确燃尽或 10 tick 没同步都立即熄火，不沿用尾烟宽限。
        return bullet.isRemoteVisualClone()
                ? RVP_ClientRemoteAmmoVisualState.isMotorBurningFresh(bullet.level().dimension().location(),
                        bullet.getId(), bullet.level().getGameTime())
                : bullet.isMotorBurningNow();
    }

    /** 将 engine_nozzle_offset 按实体俯仰/朝向旋转，与模型尾焰的 X→Y 变换顺序一致。 */
    public static Vec3 nozzle(RVP_BaseBullet bullet, Vec3 origin, float partialTick) {
        // 调用类型化喷口配置；缺失时沿用现有模型默认值 (0,0,-0.5)。
        RVP_WeaponData data = bullet.getResolvedWeaponConfig();
        float[] offset = data == null ? null : data.getProjectileData().getEngineNozzleOffset();
        Vector3f local = offset == null ? new Vector3f(0, 0, -0.5F)
                : new Vector3f(offset[0], offset[1], offset[2]);
        float pitch = Mth.lerp(partialTick, bullet.xRotO, bullet.getXRot()) * Mth.DEG_TO_RAD;
        float yaw = Mth.rotLerp(partialTick, bullet.yRotO, bullet.getYRot()) * Mth.DEG_TO_RAD;
        local.rotateX(pitch).rotateY(-yaw);
        return origin.add(local.x, local.y, local.z);
    }

    /** 每枚弹体只更新 UUID 对应的一盏灯；燃尽、配置关闭或远端超时立即删除。 */
    public static void update(Entity entity, Vec3 renderOrigin, float partialTick) {
        if (!(entity instanceof RVP_BaseBullet bullet)) { return; }
        // 调用燃烧查询，模型尾焰与环境光共用相同窗口。
        if (!isBurning(bullet)) {
            RVP_ScreenLightManager.INSTANCE.removePersistent(entity.getUUID());
            return;
        }
        // 调用代码中的统一发动机光参数；已有推进/喷口数据仍复用，颜色、半径、强度不从 JSON 读取。
        RVP_LightParameters parameters = RVP_LightParameters.engine();
        // 调用燃尽倒计时，一级充足燃烧期最亮，临近燃尽略收敛；燃尽状态仍由事实立即关闭。
        int remaining = bullet.isRemoteVisualClone()
                ? RVP_ClientRemoteAmmoVisualState.getMotorBurnRemainingTicks(bullet.level().dimension().location(), bullet.getId())
                : bullet.ticksUntilMotorStopsBurning();
        // 调用共用阶段包络，明确区分已同步的二脉冲窗口，一级保持最亮，颜色/半径随阶段变化。
        RVP_LightParameters phased = RVP_LightParameters.enginePhase(parameters, bullet.isSecondMotorPulseBurningNow(), remaining);
        // 调用持续光更新，喷口先计算再按相机位置转换，避免远坐标浮点抖动。
        RVP_ScreenLightManager.INSTANCE.upsertPersistent(bullet.getUUID(), nozzle(bullet, renderOrigin, partialTick), phased);
    }
}
