package org.ywzj.rvp.client.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ViewportEvent;
import org.ywzj.rvp.client.visual.shockwave.RVP_ShockwaveCloudField;
import org.ywzj.rvp.client.visual.shockwave.RVP_ShockwaveCloudField.*;
import org.ywzj.rvp.client.visual.shockwave.RVP_ShockwaveRenderer;
import org.ywzj.rvp.config.RVP_ClientConfig;
import org.ywzj.rvp.weapon.visual.api.RVP_ShockwaveProfile;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;
import org.ywzj.rvp.weapon.visual.RVP_ShockwaveRuntimeTuning;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 单次威尔逊云与马赫杆近似效果；完整云量与观察距离无关。 */
public final class RVP_WilsonMachEffectInstance implements RVP_ClientVisualEffect, Appearance {
    /** 所属世界，换世界后不可复用。 */
    private final ClientLevel level;
    /** 服务端事件，包含闪光、震动等开关。 */
    final RVP_VisualEffectEvent event;
    /** 服务端权威地形形状。 */
    final RVP_ShockwaveProfile profile;
    /** 基准视觉半径（格）。 */
    final float radius;
    /** 显示原点；埋爆的云团从地表冒出，空爆保持实际爆心。 */
    private final Vec3 origin;
    /** 实际持续时间（tick）；整条 220 tick 时间线按比例伸缩。 */
    private final int duration;
    /** 本次爆炸的客户端调参快照，不影响原云团时间线。 */
    final RVP_ShockwaveRuntimeTuning.Snapshot tuning;
    /** 原云团及三个前沿全部结束的时间（实际 tick）。 */
    private final int totalDuration;
    /** 各层云团与出现时刻，最多 512 个。 */
    final List<CloudBirth> clouds = new ArrayList<>();
    /** 共享球壳句柄，用于预算淘汰时立即取消。 */
    private final RVP_ShockwaveRenderer.Shockwave wave;
    /** 复用默认爆炸的近远音与声速延迟。 */
    private final RVP_DefaultExplosionSoundController sound;
    /** 相机接收事件时到爆心的距离（格）。 */
    private final double listenerDistance;
    /** 实际效果年龄（tick）。 */
    private int age;
    /** 已执行的标准化模拟年龄，限定在 0～220。 */
    private int simulatedAge;
    /** 是否已释放，关闭操作幂等。 */
    private boolean closed;

    RVP_WilsonMachEffectInstance(ClientLevel level, RVP_VisualEffectEvent event, int duration, int initialAge,
                                RVP_ShockwaveRuntimeTuning.Snapshot tuning) {
        this.level = level;
        this.event = event;
        this.profile = event.shockwaveProfile();
        this.duration = duration;
        this.tuning = tuning;
        // 调用寿命换算，调慢前沿时不会被原云团寿命提前清理。
        totalDuration = event.density() > 0 || event.flash() || event.shake()
                ? tuning.totalDuration(duration) : tuning.frontDuration(duration);
        age = initialAge;
        // 调用共享尺寸函数与服务端保持一致。
        radius = RVP_ShockwaveProfile.visualRadius(event.baseExplosionRadius(), event.scale());
        origin = new Vec3(event.position().x,
                profile.hitClass() == RVP_ShockwaveProfile.HitClass.AIR ? event.position().y : profile.groundY(),
                event.position().z);
        listenerDistance = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceTo(event.position());
        // 调用既有声音控制器，按半径选默认音色，遵守 sound 开关。
        sound = new RVP_DefaultExplosionSoundController(level, event, null, initialAge);
        createClouds();
        simulateTo((int) timeline(0));
        // 调用球面亮带服务，保留真实爆心；球面由地形深度遮挡，不再按八方向压扁形状。
        wave = RVP_ShockwaveRenderer.spawn(event.position().x, event.position().y, event.position().z,
                initialAge, duration, radius, tuning);
    }

    private void createClouds() {
        Random random = new Random(event.seed());
        int count = (int) Math.min(512.0, 384.0 * event.density());
        for (int index = 0; index < count; index++) {
            int layer = index % 5;
            double angle = random.nextDouble() * Math.PI * 2;
            int birth = layer == 0 ? 0 : layer <= 2 ? 30 + random.nextInt(35) : 60 + random.nextInt(45);
            // 使用标准化坐标模拟上升云；SHOCK 云按实际世界尺寸生成。
            double radial = layer == 0 ? random.nextDouble() : layer == 1 ? random.nextDouble() * 2 : 8 + random.nextDouble() * 5;
            double height = layer == 0 ? random.nextDouble() * 10 : layer == 1 ? 2 : layer == 3 ? 8 : 12;
            CloudType type = switch (layer) {
                case 2 -> CloudType.SHOCK;
                case 3 -> CloudType.RING;
                case 4 -> CloudType.CONDENSATION;
                default -> CloudType.STANDARD;
            };
            if (type == CloudType.SHOCK) {
                if (profile.hitClass() == RVP_ShockwaveProfile.HitClass.AIR) {
                    // 空爆的地面云预算改为凝结云，不生成悬空的尘环。
                    type = CloudType.CONDENSATION;
                } else {
                    // 调用八方向插值，让地面云随地形形成不对称轮廓。
                    radial = profile.radiusAt(angle) * (0.25 + random.nextDouble() * 0.55);
                    height = 0.2 + random.nextDouble() * radius * 0.04;
                }
            }
            float size = type == CloudType.SHOCK ? Math.max(0.2F, radius * 0.07F) : 0.8F;
            Cloud cloud = new Cloud(origin.x + Math.cos(angle) * radial, origin.y + height,
                    origin.z + Math.sin(angle) * radial, (float) -angle, 220 - birth, type, size, size * 2, random);
            if (layer == 0) { cloud.motionMult = 0.08; }
            clouds.add(new CloudBirth(birth, cloud));
        }
    }

    private void simulateTo(int target) {
        while (simulatedAge < Math.min(220, target)) {
            simulatedAge++;
            double height = 4 + simulatedAge * 0.075;
            UpdateContext context = new UpdateContext(origin.x, origin.y, origin.z,
                    height, 5 + simulatedAge * 0.025, 2, height + 2,
                    Math.max(0, 1 - simulatedAge / 220.0), Math.max(0.01, 15 * (1 - simulatedAge / 220.0)), simulatedAge);
            for (CloudBirth birth : clouds) {
                if (birth.tick() < simulatedAge) {
                    // 调用与核爆相同的运动算法，每逻辑 tick 更新，绘制阶段只插值。
                    birth.cloud().update(context);
                }
            }
        }
    }

    @Override
    public void tick() {
        age++;
        simulateTo((int) timeline(0));
        // 调用默认声音控制器推进声波，视觉清理不阻断已授权的延迟音。
        sound.tick(age);
        if (age >= duration) {
            clouds.clear();
        }
        if (age >= totalDuration) {
            // 调用共享球壳清理入口，保证视觉生命周期结束即清理。
            RVP_ShockwaveRenderer.remove(wave);
        }
    }

    /** 返回标准化的 0～220 时间线年龄。 */
    float timeline(float partialTick) { return (float) Math.min(220, (age + (double) partialTick) * 220 / duration); }

    /** 前沿使用未截断的标准年龄，支持指令延长寿命。 */
    float frontTimeline(float partialTick) { return (float) ((age + (double) partialTick) * 220 / duration); }

    @Override
    public void render(RenderLevelStageEvent renderEvent) {
        // 调用本效果的世界几何渲染；屏幕折射由全局共享入口统一处理。
        RVP_WilsonMachRenderer.render(this, renderEvent, false);
    }

    @Override
    public void renderThermal(RenderLevelStageEvent renderEvent) {
        // 复用世界几何写入已绑定的热成像缓冲，不复制场景或切换绘制目标。
        RVP_WilsonMachRenderer.render(this, renderEvent, true);
    }

    @Override
    public void applyCameraShake(ViewportEvent.ComputeCameraAngles cameraEvent) {
        double arrival = listenerDistance / 17.15;
        double shakeAge = age + cameraEvent.getPartialTick() - arrival;
        if (!event.shake() || closed || shakeAge < 0 || shakeAge >= 12) { return; }
        // 调用既有客户端震动设置，避免绕过用户的强度选择。
        float amount = (float) (Math.max(0, 1 - listenerDistance / Math.max(48, radius * 4))
                * (1 - shakeAge / 12) * RVP_ClientConfig.getExplosionShakeIntensity());
        cameraEvent.setPitch(cameraEvent.getPitch() + (float) Math.sin(shakeAge * 2.6) * amount * 0.5F);
    }

    @Override
    public boolean isFinished() { return closed || level != Minecraft.getInstance().level || age >= totalDuration && sound.isFinished(); }

    @Override
    public void close() {
        closed = true;
        clouds.clear();
        // 分别释放本实例的延迟音与折射球壳，不影响其它实例。
        sound.close();
        RVP_ShockwaveRenderer.remove(wave);
    }

    public Vec3 center() { return origin; }
    public float cloudScale() { return radius / 24; }
    public int age() { return simulatedAge; }
    public int maxAge() { return 220; }
    public double greying() { return 2; }

    /** @param tick 出现时刻（标准化 tick） @param cloud 共享云团状态 */
    record CloudBirth(int tick, RVP_ShockwaveCloudField.Cloud cloud) { }
}
