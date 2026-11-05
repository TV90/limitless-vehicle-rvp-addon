package org.ywzj.rvp.client.light;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.state.RVP_ClientHitlState;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.data.RVP_EffectsData;
import org.ywzj.rvp.weapon.visual.RVP_RocketFlameRuntimeTuning;
import org.ywzj.rvp.weapon.visual.RVP_RocketTrailParameters;

/** 近距客户端桥和远距渲染入口共用的配置解析；只登记世界烟段，不生成贴图粒子。 */
public final class RVP_RocketTrailEmitter {
    private RVP_RocketTrailEmitter() { }

    /** 以同步实体编号合并近距与远距来源，保留旧 JSON 的尺寸、密度、生成间隔和消散时长。 */
    public static void emit(RVP_BaseBullet bullet, RVP_EffectsData effects, Vec3 position, Vec3 exhaust,
            float scale, int remainingBurnTicks, boolean remote) {
        Minecraft mc = Minecraft.getInstance();
        if (!RVP_RocketTrailParameters.volumeEnabled() || mc.level == null || bullet.level() != mc.level
                || effects == null || !effects.isMissileNativeTrailRocketFlame() || mc.isPaused()) { return; }
        // 调用世界身份检查，客户端 tick 早于首个渲染帧时也能安全登记。
        RVP_RocketTrailManager manager = RVP_RocketTrailManager.INSTANCE;
        manager.beginWorld(mc.level, mc.level.getGameTime());
        // 登记 UUID，近距实体、真实克隆和虚拟显示接管同一编号时不能连接别的弹的烟。
        manager.bindIdentity(bullet.getId(), bullet.getUUID());
        if (!Float.isFinite(scale) || scale <= 0) {
            // 调用公共弹体停烟门控约定的零尺寸断链，不抹掉已经存在的世界烟段。
            manager.stop(bullet.getId()); return;
        }
        // 调用原有总开关与 TV 导弹近场屏蔽；关掉后断开新路径，保留先前已排出的烟。
        if (!RVP_RocketFlameRuntimeTuning.resolveEnabled(effects.isMissileNativeTrailEnabled())
                || RVP_ClientHitlState.shouldSuppressParticleNearActiveMissile(position.x, position.y, position.z)) {
            manager.stop(bullet.getId()); return;
        }
        int interval = Math.max(1, RVP_RocketFlameRuntimeTuning.resolveSpawnInterval(
                effects.getMissileNativeTrailSpawnIntervalTick()));
        if (mc.level.getGameTime() % interval != 0) { return; }
        float density = RVP_RocketFlameRuntimeTuning.resolveDensityScale(effects.getMissileNativeTrailDensityScale());
        if (!Float.isFinite(density) || density <= 0) {
            manager.stop(bullet.getId()); return;
        }
        int fade = RVP_RocketFlameRuntimeTuning.resolveFadeTicks(effects.getMissileNativeTrailFadeTicks());
        int randomFade = RVP_RocketFlameRuntimeTuning.resolveFadeRandomTicks(effects.getMissileNativeTrailFadeRandomTicks());
        float phase = random(bullet.getId(), mc.level.getGameTime());
        long requestedFade = (long) fade + (randomFade <= 0 ? 0 : (int) (phase * randomFade));
        var duration = RVP_RocketTrailParameters.resolveLifetime(
                remainingBurnTicks, effects.getMissileNativeTrailHoldMinTicks(), requestedFade);
        float alphaRatio = RVP_RocketFlameRuntimeTuning.resolveAlphaHoldRatio(effects.getMissileNativeTrailAlphaHoldRatio());
        float grey = Math.max(0, Math.min(1, RVP_RocketFlameRuntimeTuning.resolveSmokeGreyMin()
                + RVP_RocketFlameRuntimeTuning.resolveSmokeGreySpread() * random(bullet.getId(), 0)));
        float step = RVP_RocketFlameRuntimeTuning.resolveStep((float) effects.getMissileNativeTrailStep());
        // 旧的采样间距改为烟段细分精度；不再按 0.5 格逐颗创建粒子，浓度由 density 独立控制。
        var profile = new RVP_RocketTrailManager.Profile(Math.min(scale, 64), Math.min(density, 16),
                duration.holdTicks(), duration.fadeTicks(), alphaRatio, grey,
                Math.max(4, Math.min(32, step * 8 / Math.max(0.05, density))), Math.max(4, interval * 3), false);
        // 调用统一世界烟段缓存，远距克隆和本地实体不分别制造两份尾烟。
        manager.append(bullet.getId(), position, exhaust, profile, remote);
    }

    /** 新贴地烟浪沿用旧开关与尺寸，只将实际绘制切换为独立的体积烟团。 */
    public static void wash(RVP_BaseBullet bullet, Vec3 position, float scale) {
        Minecraft mc = Minecraft.getInstance();
        if (!RVP_RocketTrailParameters.volumeEnabled() || mc.level == null || bullet.level() != mc.level
                || mc.isPaused() || !Float.isFinite(scale) || scale <= 0) { return; }
        RVP_RocketTrailManager manager = RVP_RocketTrailManager.INSTANCE;
        // 调用同一世界检查；烟浪和空中尾烟分别去重，不相互吞掉采样。
        manager.beginWorld(mc.level, mc.level.getGameTime());
        // 烟浪与空中尾烟共用来源身份，防会话编号复用接上旧路径。
        manager.bindIdentity(bullet.getId(), bullet.getUUID());
        int lifetime = Math.max(40, Math.min(600, Math.round((80 + 20 * random(bullet.getId(), mc.level.getGameTime())) * scale)));
        // 调用独立冲刷烟密度，增加遮盖程度，不连带加浓空中尾烟或延长烟团寿命。
        var profile = new RVP_RocketTrailManager.Profile(Math.min(scale, 64), RVP_RocketTrailParameters.WASH_DENSITY, 0, lifetime, 0,
                0.62F, 4, 4, true);
        for (int index = 0; index < RVP_RocketTrailParameters.WASH_PUFFS_PER_TICK; index++) {
            double angle = (random(bullet.getId() + index * 173, mc.level.getGameTime()) + index * 0.5) * Math.PI * 2;
            // 调用球形烟团登记，径向初速负责在地面铺开，旧 WASH 粒子不会参与。
            manager.addWash(bullet.getId(), position, new Vec3(Math.cos(angle) * 0.24, 0.015, Math.sin(angle) * 0.24), profile);
        }
    }

    /** 按来源和游戏 tick 产生稳定的 0～1 数值；同 tick 多帧不重掷，暂停不会闪动。 */
    private static float random(int id, long tick) {
        long value = (id * 0x9E3779B97F4A7C15L) ^ (tick * 0xBF58476D1CE4E5B9L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        return ((value ^ (value >>> 27)) & 0xFFFFFFL) / 16777216F;
    }
}
