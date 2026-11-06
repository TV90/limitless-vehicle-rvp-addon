package org.ywzj.rvp.client.visual.thermobaric;

import org.ywzj.rvp.client.performance.RVP_RenderMetric;
import org.ywzj.rvp.client.performance.RVP_RenderStage;
import org.ywzj.rvp.client.performance.RVP_RenderPerformance;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.RenderGuiEvent;
import org.ywzj.rvp.client.visual.shake.RVP_ScreenShakeParameters;
import org.ywzj.rvp.client.visual.shake.RVP_ScreenShakeScheduler;
import org.ywzj.rvp.config.RVP_ClientConfig;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** 温压闪光服务与通用震动调度器的温压参数入口。 */
public final class RVP_ThermobaricScreenFeedback {
    /** 闪光总持续时间。 */
    static final int FLASH_DURATION_TICKS = 10;
    /** 单次闪光在客户端倍率为 1 时的最大透明度。 */
    static final float MAX_FLASH_ALPHA = 0.65F;
    /** 单次震动总持续时间。 */
    static final int SHAKE_DURATION_TICKS = 12;
    /** 多次震动叠加后每个相机轴允许的最大角度。 */
    static final float MAX_COMBINED_SHAKE_DEGREES = 2.0F;
    /** 保留原温压寿命、三轴角度和角频率；仅由本调用方定义，不写入通用执行器。 */
    static final RVP_ScreenShakeParameters SHAKE_PARAMETERS = new RVP_ScreenShakeParameters(
            SHAKE_DURATION_TICKS, 0.90F, 0.70F, 0.50F, 2.17D, 2.63D, 1.91D);
    /** 当前世界中按事件键去重的闪光脉冲。 */
    private static final Map<RVP_ThermobaricSoundController.EventKey, FlashPulse> FLASHES =
            new HashMap<>();
    /** 温压专属调度器；通过客户端配置实时读取原温压强度倍率，与其他调用方隔离。 */
    private static final RVP_ScreenShakeScheduler SHAKES = new RVP_ScreenShakeScheduler(
            MAX_COMBINED_SHAKE_DEGREES, RVP_ClientConfig::getThermobaricShakeIntensity);

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
        // 提交原温压参数，在声波抵达这一刻立即开始；距离衰减与相机写入由通用系统处理。
        SHAKES.schedule(key, level.getGameTime(), level.getGameTime(), seed,
                listenerDistance, range, SHAKE_PARAMETERS);
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
        // 调用通用性能范围，默认关闭时不申请显卡查询；所有提前返回和异常均自动封口。
        try (var performance = RVP_RenderPerformance.gui(RVP_RenderStage.THERMOBARIC_FLASH, graphics)) {
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE);
            RenderSystem.depthMask(false);
            // 调用逻辑面片及物理像素上界统计，实际批处理draw次数不在这里猜测。
            RVP_RenderPerformance.guiQuad(graphics, graphics.guiWidth(), graphics.guiHeight());
            graphics.fill(0, 0, event.getWindow().getGuiScaledWidth(),
                    event.getWindow().getGuiScaledHeight(), (alpha << 24) | 0xFFFFFF);
            graphics.flush();
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    /** 取消指定实例的反馈脉冲。 */
    static void cancel(RVP_ThermobaricSoundController.EventKey key) {
        FLASHES.remove(key);
        // 撤销温压调度器内的对应脉冲，不影响其他调用方的同名事件。
        SHAKES.cancel(key);
    }

    /** 清空当前世界的全部反馈。 */
    static void clear() {
        FLASHES.clear();
        // 仅清空温压调用方的震动，世界级清理由通用执行器负责。
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
        // 闪光与震动共用原有距离公式，避免维护两份相同算法。
        return RVP_ScreenShakeScheduler.resolveDistanceFactor(distance, range);
    }

    /** 返回脉冲生命周期进度。 */
    static float resolveProgress(double now, double start, int durationTicks) {
        if (durationTicks <= 0) {
            return 1.0F;
        }
        return (float) Mth.clamp((now - start) / durationTicks, 0.0D, 1.0D);
    }

    /**
     * 单次闪光脉冲。
     * @param startGameTime 闪光开始的世界时间，单位游戏刻
     * @param distanceFactor 线性距离衰减倍率，范围 0 至 1
     */
    private record FlashPulse(double startGameTime, float distanceFactor) {
    }
}
