package org.ywzj.rvp.client.visual.thermobaric;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ViewportEvent;
import org.ywzj.rvp.config.RVP_ClientConfig;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** 温压闪光与镜头震动的专用客户端反馈服务。 */
public final class RVP_ThermobaricScreenFeedback {
    /** 闪光总持续时间。 */
    static final int FLASH_DURATION_TICKS = 10;
    /** 单次闪光在客户端倍率为 1 时的最大透明度。 */
    static final float MAX_FLASH_ALPHA = 0.65F;
    /** 单次震动总持续时间。 */
    static final int SHAKE_DURATION_TICKS = 12;
    /** 多次震动叠加后每个相机轴允许的最大角度。 */
    static final float MAX_COMBINED_SHAKE_DEGREES = 2.0F;
    /** 当前世界中按事件键去重的闪光脉冲。 */
    private static final Map<RVP_ThermobaricSoundController.EventKey, FlashPulse> FLASHES =
            new HashMap<>();
    /** 当前世界中按事件键去重的震动脉冲。 */
    private static final Map<RVP_ThermobaricSoundController.EventKey, ShakePulse> SHAKES =
            new HashMap<>();

    private RVP_ThermobaricScreenFeedback() {
    }

    /** 注册立即到达的闪光，并按网络补帧年龄跳过已经过去的部分。 */
    static void triggerFlash(RVP_ThermobaricSoundController.EventKey key, ClientLevel level,
            int initialAge, double listenerDistance, float visualRadius) {
        if (initialAge >= FLASH_DURATION_TICKS) {
            return;
        }
        double range = Math.max(24.0D, Math.max(0.0F, visualRadius) * 8.0D);
        float distanceFactor = resolveDistanceFactor(listenerDistance, range);
        if (distanceFactor <= 0.0F) {
            return;
        }
        double startGameTime = level.getGameTime() - Math.max(0, initialAge);
        FLASHES.putIfAbsent(key, new FlashPulse(startGameTime, distanceFactor));
    }

    /** 在声波抵达时注册确定性镜头震动。 */
    static void triggerShake(RVP_ThermobaricSoundController.EventKey key, ClientLevel level,
            long seed, double listenerDistance, float visualRadius) {
        double range = Math.max(48.0D, Math.max(0.0F, visualRadius) * 12.0D);
        float distanceFactor = resolveDistanceFactor(listenerDistance, range);
        if (distanceFactor <= 0.0F) {
            return;
        }
        SHAKES.putIfAbsent(key, new ShakePulse(level.getGameTime(), seed, distanceFactor));
    }

    /** 在 GUI 最后阶段绘制当前最强闪光，多个事件不会通过相加造成过曝。 */
    public static void renderFlash(RenderGuiEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || FLASHES.isEmpty()) {
            return;
        }
        double now = level.getGameTime() + event.getPartialTick();
        float strongest = 0.0F;
        Iterator<FlashPulse> iterator = FLASHES.values().iterator();
        while (iterator.hasNext()) {
            FlashPulse pulse = iterator.next();
            float progress = resolveProgress(now, pulse.startGameTime(), FLASH_DURATION_TICKS);
            if (progress >= 1.0F) {
                iterator.remove();
                continue;
            }
            strongest = Math.max(strongest, (1.0F - progress) * pulse.distanceFactor());
        }
        // 调用客户端配置读取实时闪光上限，使无障碍设置可立即生效。
        float alphaFactor = strongest * MAX_FLASH_ALPHA
                * RVP_ClientConfig.getThermobaricFlashIntensity();
        int alpha = Mth.clamp(Math.round(alphaFactor * 255.0F), 0, 255);
        if (alpha <= 0) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        graphics.fill(0, 0, event.getWindow().getGuiScaledWidth(),
                event.getWindow().getGuiScaledHeight(), (alpha << 24) | 0xFFFFFF);
        graphics.flush();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    /** 通过 Forge 相机角度事件叠加当前全部温压震动。 */
    public static void applyCameraShake(ViewportEvent.ComputeCameraAngles event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || SHAKES.isEmpty()) {
            return;
        }
        double now = level.getGameTime() + event.getPartialTick();
        float yaw = 0.0F;
        float pitch = 0.0F;
        float roll = 0.0F;
        Iterator<ShakePulse> iterator = SHAKES.values().iterator();
        while (iterator.hasNext()) {
            ShakePulse pulse = iterator.next();
            float progress = resolveProgress(now, pulse.startGameTime(), SHAKE_DURATION_TICKS);
            if (progress >= 1.0F) {
                iterator.remove();
                continue;
            }
            ShakeOffset offset = resolveShakeOffset(
                    now - pulse.startGameTime(), progress, pulse.seed(), pulse.distanceFactor());
            yaw += offset.yaw();
            pitch += offset.pitch();
            roll += offset.roll();
        }
        // 调用客户端配置读取实时震动上限；该倍率与声音和闪光互不关联。
        float clientScale = RVP_ClientConfig.getThermobaricShakeIntensity();
        event.setYaw(event.getYaw() + clampShake(yaw * clientScale));
        event.setPitch(event.getPitch() + clampShake(pitch * clientScale));
        event.setRoll(event.getRoll() + clampShake(roll * clientScale));
    }

    /** 取消指定实例的反馈脉冲。 */
    static void cancel(RVP_ThermobaricSoundController.EventKey key) {
        FLASHES.remove(key);
        SHAKES.remove(key);
    }

    /** 清空当前世界的全部反馈。 */
    static void clear() {
        FLASHES.clear();
        SHAKES.clear();
    }

    /** 清空当前世界的声音去重与全部温压反馈状态。 */
    public static void clearAll() {
        // 调用温压声音控制器清空跨实例去重键，保证新世界不会继承旧世界事件。
        RVP_ThermobaricSoundController.clear();
        clear();
    }

    /** 返回线性距离衰减；无效距离视作爆心位置。 */
    static float resolveDistanceFactor(double distance, double range) {
        if (!Double.isFinite(range) || range <= 0.0D) {
            return 0.0F;
        }
        double normalizedDistance = Double.isFinite(distance) ? Math.max(0.0D, distance) : 0.0D;
        return (float) Mth.clamp(1.0D - normalizedDistance / range, 0.0D, 1.0D);
    }

    /** 返回脉冲生命周期进度。 */
    static float resolveProgress(double now, double start, int durationTicks) {
        if (durationTicks <= 0) {
            return 1.0F;
        }
        return (float) Mth.clamp((now - start) / durationTicks, 0.0D, 1.0D);
    }

    /** 按 seed 和连续年龄计算可复现的三轴震动。 */
    static ShakeOffset resolveShakeOffset(double age, float progress, long seed,
            float distanceFactor) {
        float envelope = (1.0F - Mth.clamp(progress, 0.0F, 1.0F));
        envelope = envelope * envelope * Mth.clamp(distanceFactor, 0.0F, 1.0F);
        double phaseA = ((seed >>> 8) & 0xFFFFL) / 65535.0D * Math.PI * 2.0D;
        double phaseB = ((seed >>> 24) & 0xFFFFL) / 65535.0D * Math.PI * 2.0D;
        double phaseC = ((seed >>> 40) & 0xFFFFL) / 65535.0D * Math.PI * 2.0D;
        return new ShakeOffset(
                (float) Math.sin(age * 2.17D + phaseA) * 0.90F * envelope,
                (float) Math.sin(age * 2.63D + phaseB) * 0.70F * envelope,
                (float) Math.sin(age * 1.91D + phaseC) * 0.50F * envelope);
    }

    /** 把多事件叠加震动限制在适度范围内。 */
    static float clampShake(float value) {
        return Mth.clamp(value, -MAX_COMBINED_SHAKE_DEGREES,
                MAX_COMBINED_SHAKE_DEGREES);
    }

    /** 单次闪光脉冲。 */
    private record FlashPulse(double startGameTime, float distanceFactor) {
    }

    /** 单次震动脉冲。 */
    private record ShakePulse(double startGameTime, long seed, float distanceFactor) {
    }

    /** 三轴镜头偏移。 */
    record ShakeOffset(float yaw, float pitch, float roll) {
    }
}
