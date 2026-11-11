package org.ywzj.rvp.client.light;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteAmmoVisualState;
import org.ywzj.rvp.config.RVP_LightingConfig;
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
        // 无推力/无燃烧窗口的弹体（炸弹等）不算发动机：否则 misconfigured 的 engine 标记会让喷焰常亮。
        if (data == null || !data.getProjectileData().hasRocketEngine()
                || !data.getProjectileData().usesPropulsion()) { return false; }
        // 按对象引用识别独立虚拟显示实例，不能把同 UUID 雷达代理的默认发动机字段当权威事实。
        var visual = org.ywzj.rvp.client.state.remotevisibility.RVP_ClientVirtualMissileVisualState.owned(bullet);
        if (visual != null) return visual.timeline().motorPhase(
                org.ywzj.rvp.client.state.remotevisibility.RVP_ClientVirtualMissileVisualState.animationTick())
                != org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot.MotorPhase.IDLE;
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
        // 调用共用观瞄伪装平移，避免喷焰与“从炮口射出”的临时视觉弹道分离。
        return origin.add(local.x, local.y, local.z).add(
                org.ywzj.rvp.client.render.RVP_SightFireDisguiseRender.renderOffset(bullet, partialTick));
    }

    /** 从弹体局部 Z- 轴取得喷流朝向，与模型的 X→Y 旋转顺序一致，不按速度猜测喷口方向。 */
    public static Vec3 exhaustDirection(RVP_BaseBullet bullet, float partialTick) {
        float pitch = Mth.lerp(partialTick, bullet.xRotO, bullet.getXRot()) * Mth.DEG_TO_RAD;
        float yaw = Mth.rotLerp(partialTick, bullet.yRotO, bullet.getYRot()) * Mth.DEG_TO_RAD;
        Vector3f direction = new Vector3f(0, 0, -1).rotateX(pitch).rotateY(-yaw);
        return new Vec3(direction.x, direction.y, direction.z);
    }

    /** 每枚弹体只更新 UUID 对应的一盏灯；燃尽、配置关闭或远端超时立即删除。 */
    public static void update(Entity entity, Vec3 renderOrigin, float partialTick) {
        if (!(entity instanceof RVP_BaseBullet bullet)) { return; }
        // 调用喷焰世界检查，远端更新也不依赖本地发动机是否存在。
        RVP_RocketPlumeManager.INSTANCE.beginWorld(bullet.level(), bullet.level().getGameTime());
        // 调用燃烧查询，模型尾焰与环境光共用相同窗口。
        // 调用已有 HITL 自身屏蔽门，停止登记并清除缓存的程序喷焰及发动机光晕。
        // 本地实体和超视距克隆都经过本入口，外部观察其它导弹的效果保持原状。
        if (org.ywzj.rvp.client.state.RVP_ClientHitlState.shouldHideActiveMissileVfx(bullet)
                || !isBurning(bullet)) {
            RVP_ScreenLightManager.INSTANCE.removePersistent(entity.getUUID());
            RVP_RocketPlumeManager.INSTANCE.remove(entity.getUUID());
            return;
        }
        // 调用代码中的统一发动机光参数；已有推进/喷口数据仍复用，颜色、半径、强度不从 JSON 读取。
        // 调用既有弹体口径，缺失时只采用二十格基础范围，不混用曳光口径或新增 JSON 光源字段。
        var effects = bullet.getResolvedWeaponConfig().getEffectsData();
        float caliber = effects.hasCaliberOverride() ? effects.getCaliber() : 0F;
        RVP_LightParameters parameters = RVP_LightParameters.engine(caliber);
        // 调用燃尽倒计时，一级充足燃烧期最亮，临近燃尽略收敛；燃尽状态仍由事实立即关闭。
        // 虚拟对象未运行发动机 Tick，阶段、当前窗口和亮度倒计时必须从同一时间线读取。
        var virtualVisual = org.ywzj.rvp.client.state.remotevisibility.RVP_ClientVirtualMissileVisualState.owned(bullet);
        long animationTick = org.ywzj.rvp.client.state.remotevisibility.RVP_ClientVirtualMissileVisualState.animationTick();
        int remaining = virtualVisual != null ? virtualVisual.timeline().motorRemaining(animationTick) : bullet.isRemoteVisualClone()
                ? RVP_ClientRemoteAmmoVisualState.getMotorBurnRemainingTicks(bullet.level().dimension().location(),
                        bullet.getId(), bullet.level().getGameTime())
                : bullet.ticksUntilMotorStopsBurning();
        // 调用共用阶段包络，二脉冲改变颜色/强度，口径决定的共同半径保持不变。
        boolean secondPulse = virtualVisual != null ? virtualVisual.timeline().motorPhase(animationTick)
                == org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot.MotorPhase.SECOND_PULSE
                : bullet.isSecondMotorPulseBurningNow();
        RVP_LightParameters phased = RVP_LightParameters.enginePhase(parameters, secondPulse, remaining);
        // 调用持续光更新，喷口先计算再按相机位置转换，避免远坐标浮点抖动。
        Vec3 position = nozzle(bullet, renderOrigin, partialTick);
        // 调用分组开关：照亮走 engineLight，广域橙晕走 missileHalo；任一开启都需要这盏持续光。
        boolean surfaceLight = RVP_LightingConfig.engineLightEnabled();
        boolean halo = RVP_LightingConfig.missileHaloEnabled();
        if (surfaceLight || halo) {
            RVP_ScreenLightManager.INSTANCE.upsertPersistent(bullet.getUUID(), position, phased);
        } else {
            RVP_ScreenLightManager.INSTANCE.removePersistent(bullet.getUUID());
        }
        // 调用独立喷流登记，稳态复用 flame_scale；点火/燃尽平滑调整喷流尺寸和最终显示亮度。
        if (RVP_LightingConfig.plumeEnabled()) {
            // 调用当前燃烧窗口出口，一级收束不能被未来二脉冲的尾烟保持期拖后；虚拟/远端继续服从权威快照。
            int plumeRemaining = virtualVisual != null || bullet.isRemoteVisualClone()
                    ? remaining : bullet.ticksUntilCurrentMotorStopsBurning();
            RVP_RocketPlumeManager.INSTANCE.upsertEngine(bullet.getUUID(), position, exhaustDirection(bullet, partialTick),
                    bullet.getResolvedWeaponConfig().getProjectileData().getFlameScale(),
                    caliber, phased.intensity() / RVP_LightParameters.ENGINE_SURFACE_INTENSITY, bullet.getId(), plumeRemaining);
        } else {
            RVP_RocketPlumeManager.INSTANCE.remove(bullet.getUUID());
        }
    }
}
