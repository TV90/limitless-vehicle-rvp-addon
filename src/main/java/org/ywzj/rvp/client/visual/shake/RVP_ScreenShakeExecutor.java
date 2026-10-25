package org.ywzj.rvp.client.visual.shake;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.ViewportEvent;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/** 通用镜头震动执行器：计算参数化波形，通过既有 Forge 相机事件统一写入三轴角度。 */
public final class RVP_ScreenShakeExecutor {
    /** 当前有任务的调度器，按首次激活顺序执行；只在客户端主线程访问。 */
    private static final Set<RVP_ScreenShakeScheduler> ACTIVE = new LinkedHashSet<>();

    private RVP_ScreenShakeExecutor() {
    }

    /** 由调度器在提交任务后登记，同一调度器不会重复执行。 */
    static void activate(RVP_ScreenShakeScheduler scheduler) {
        ACTIVE.add(scheduler);
    }

    /** 由唯一的客户端相机事件入口调用，保留原镜头角度并叠加所有调用方的结果。 */
    public static void applyCameraShake(ViewportEvent.ComputeCameraAngles event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || ACTIVE.isEmpty()) {
            return;
        }
        // 统一采样世界时间与帧内插值，避免不同调用方的震动节奏偏移。
        ShakeOffset offset = sampleAll(level.getGameTime() + event.getPartialTick());
        event.setYaw(event.getYaw() + offset.yaw());
        event.setPitch(event.getPitch() + offset.pitch());
        event.setRoll(event.getRoll() + offset.roll());
    }

    /** 汇总各调用方分别限幅后的结果；不额外施加温压专属上限。 */
    static ShakeOffset sampleAll(double now) {
        float yaw = 0.0F;
        float pitch = 0.0F;
        float roll = 0.0F;
        Iterator<RVP_ScreenShakeScheduler> iterator = ACTIVE.iterator();
        while (iterator.hasNext()) {
            RVP_ScreenShakeScheduler scheduler = iterator.next();
            // 调用调度器清理到期任务并按自己的实时倍率、叠加上限取得结果。
            ShakeOffset offset = scheduler.sample(now);
            yaw += offset.yaw();
            pitch += offset.pitch();
            roll += offset.roll();
            // 释放已经完成任务的调用方引用，避免持续保留临时调度器。
            if (scheduler.isEmpty()) {
                iterator.remove();
            }
        }
        return new ShakeOffset(yaw, pitch, roll);
    }

    /** 切世界或断开连接时清理所有调用方的活动任务；调度器本身可继续复用。 */
    public static void clearAll() {
        for (RVP_ScreenShakeScheduler scheduler : ACTIVE) {
            // 清除每个调用方的等待和活动脉冲，防止旧世界震动带到新世界。
            scheduler.clear();
        }
        ACTIVE.clear();
    }

    /** 执行一个调用方的全部任务，严格保留“先叠加、再乘倍率、最后限幅”的顺序。 */
    static ShakeOffset execute(Collection<RVP_ScreenShakeScheduler.Pulse> pulses,
            double now, float scale, float maxDegrees) {
        float yaw = 0.0F;
        float pitch = 0.0F;
        float roll = 0.0F;
        for (RVP_ScreenShakeScheduler.Pulse pulse : pulses) {
            double age = now - pulse.startGameTime();
            if (age < 0.0D) {
                continue;
            }
            // 使用任务自己的寿命和三轴参数计算当前偏移，随机相位仍由同一个种子决定。
            ShakeOffset offset = resolveOffset(age, pulse.seed(), pulse.distanceFactor(), pulse.parameters());
            yaw += offset.yaw();
            pitch += offset.pitch();
            roll += offset.roll();
        }
        return new ShakeOffset(Mth.clamp(yaw * scale, -maxDegrees, maxDegrees),
                Mth.clamp(pitch * scale, -maxDegrees, maxDegrees),
                Mth.clamp(roll * scale, -maxDegrees, maxDegrees));
    }

    /** 按连续年龄计算确定性波形；乘法顺序、种子取位和平方衰减均沿用原温压实现。 */
    public static ShakeOffset resolveOffset(double age, long seed, float distanceFactor,
            RVP_ScreenShakeParameters parameters) {
        float progress = (float) Mth.clamp(age / parameters.durationTicks(), 0.0D, 1.0D);
        float envelope = 1.0F - progress;
        envelope = envelope * envelope * Mth.clamp(distanceFactor, 0.0F, 1.0F);
        double phaseA = ((seed >>> 8) & 0xFFFFL) / 65535.0D * Math.PI * 2.0D;
        double phaseB = ((seed >>> 24) & 0xFFFFL) / 65535.0D * Math.PI * 2.0D;
        double phaseC = ((seed >>> 40) & 0xFFFFL) / 65535.0D * Math.PI * 2.0D;
        return new ShakeOffset(
                (float) Math.sin(age * parameters.yawRadiansPerTick() + phaseA) * parameters.yawDegrees() * envelope,
                (float) Math.sin(age * parameters.pitchRadiansPerTick() + phaseB) * parameters.pitchDegrees() * envelope,
                (float) Math.sin(age * parameters.rollRadiansPerTick() + phaseC) * parameters.rollDegrees() * envelope);
    }

    /**
     * 三轴镜头偏移。
     * @param yaw 左右转动角度，单位度
     * @param pitch 上下转动角度，单位度
     * @param roll 画面倾斜角度，单位度
     */
    public record ShakeOffset(float yaw, float pitch, float roll) {
    }
}
