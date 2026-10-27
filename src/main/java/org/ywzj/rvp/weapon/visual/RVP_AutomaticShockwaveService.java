package org.ywzj.rvp.weapon.visual;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.server.visual.RVP_NetworkVisualEventPublisher;
import org.ywzj.rvp.server.visual.RVP_ShockwaveProfileSampler;
import org.ywzj.rvp.weapon.visual.api.RVP_ShockwaveProfile;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

/** 大半径爆炸的附加冲击波：复用现有球壳、地面环和马赫杆，不参与主爆炸视觉的互斥。 */
public final class RVP_AutomaticShockwaveService {
    /** 自动启用的配置半径下限（格），包含恰好 8 格。 */
    private static final float MIN_RADIUS = 8.0F;
    /** 纯视觉失败只记录日志，不能中断真实爆炸。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 复用通用视觉消息发布端，不增加网络字段。 */
    private static final RVP_NetworkVisualEventPublisher PUBLISHER = new RVP_NetworkVisualEventPublisher();

    private RVP_AutomaticShockwaveService() { }

    /** 只按 explosion_data.radius 判断；引信、水下和地形破坏半径均不改变启用门槛。 */
    static boolean isEnabled(float configuredRadius) {
        return Float.isFinite(configuredRadius) && configuredRadius >= MIN_RADIUS;
    }

    /** 在普通爆炸或 HBM 改写地形前取得轮廓；返回空表示未达到门槛或采样失败。 */
    @Nullable
    public static RVP_VisualEffectEvent prepare(ServerLevel level, Vec3 position, float configuredRadius) {
        // 调用统一门槛判断，小爆炸不承担地形采样开销。
        if (!isEnabled(configuredRadius)) { return null; }
        try {
            // 调用现有八方向采样，只读取已加载地形，不额外生成区块。
            RVP_ShockwaveProfile profile = RVP_ShockwaveProfileSampler.sample(level, position, configuredRadius, 1.0F);
            // 调用无副作用的事件构造，固定关闭附加云团、声音、闪光与震动。
            return createEvent(level.dimension(), position, configuredRadius, profile,
                    level.random.nextLong(), level.getGameTime());
        } catch (RuntimeException exception) {
            LOGGER.warn("无法准备 RVP 自动冲击波，保留原爆炸流程", exception);
            return null;
        }
    }

    /** 构造仅包含三种冲击前沿的事件；尺寸使用配置半径，沿用既有 220 tick 时间线。 */
    static RVP_VisualEffectEvent createEvent(ResourceKey<Level> dimension, Vec3 position,
            float configuredRadius, RVP_ShockwaveProfile profile, long seed, long startGameTime) {
        return new RVP_VisualEffectEvent(RVP_ShockwaveProfileSampler.EFFECT_TYPE,
                ResourceLocation.fromNamespaceAndPath("rvp", "default"), "{}", dimension, position,
                configuredRadius, 1.0F, 0.0F, 220, 8192.0D, seed, startGameTime,
                false, false, false, false, profile);
    }

    /** 独立追加效果，不计入主视觉发布数量，避免误屏蔽默认爆炸；已有完整冲击波时不重复。 */
    public static void publish(ServerLevel level, @Nullable RVP_VisualEffectEvent event,
            boolean shockwaveAlreadyPublished) {
        if (event == null || shockwaveAlreadyPublished) { return; }
        try {
            // 调用通用网络发布端，客户端直接复用已注册的威尔逊冲击波工厂。
            PUBLISHER.publish(level, event);
        } catch (RuntimeException exception) {
            LOGGER.warn("无法发布 RVP 自动冲击波，保留原爆炸流程", exception);
        }
    }

    /** 只有实际可显示的完整威尔逊事件才能代替自动效果，禁用尺寸或时长不能阻止补充。 */
    static boolean providesShockwave(RVP_VisualEffectEvent event) {
        return RVP_ShockwaveProfileSampler.EFFECT_TYPE.equals(event.effectType())
                && event.shockwaveProfile() != null && event.scale() > 0.0F && event.durationTicks() != 0;
    }
}
