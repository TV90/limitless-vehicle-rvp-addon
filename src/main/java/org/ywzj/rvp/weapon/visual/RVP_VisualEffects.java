package org.ywzj.rvp.weapon.visual;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.ywzj.rvp.weapon.data.RVP_VisualEffectData;
import org.ywzj.rvp.weapon.visual.api.RVP_DetonationVisualContext;
import org.ywzj.rvp.weapon.visual.api.RVP_DetonationVisualResolver;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEventPublisher;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualPublishResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** 公共视觉协调门面；默认 no-op publisher 保证初始化前和专服类加载安全。 */
public final class RVP_VisualEffects {
    /** 日志记录器。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 初始化前的空发布端。 */
    private static final RVP_VisualEventPublisher NO_OP = (level, event) -> { };
    /** 当前服务端发布端。 */
    private static volatile RVP_VisualEventPublisher publisher = NO_OP;
    /** 防止公共初始化重复安装网络发布端。 */
    private static final AtomicBoolean PUBLISHER_INSTALLED = new AtomicBoolean();
    /** 按注册顺序解析视觉配置的公共解析器列表。 */
    private static final CopyOnWriteArrayList<RVP_DetonationVisualResolver> RESOLVERS =
            new CopyOnWriteArrayList<>(List.of(new RVP_DefaultDetonationVisualResolver()));

    private RVP_VisualEffects() {
    }

    public static void installPublisher(RVP_VisualEventPublisher newPublisher) {
        Objects.requireNonNull(newPublisher, "newPublisher");
        if (!PUBLISHER_INSTALLED.compareAndSet(false, true)) {
            throw new IllegalStateException("RVP visual event publisher is already installed");
        }
        publisher = newPublisher;
    }

    /** 注册高优先级扩展解析器；未命中时再回退到当前阶段的通用解析器。 */
    public static void registerResolver(RVP_DetonationVisualResolver resolver) {
        RESOLVERS.add(0, Objects.requireNonNull(resolver, "resolver"));
    }

    /**
     * 解析并发布一次爆炸的全部视觉配置。只有成功发布且该项明确要求时才返回本体视觉抑制标记。
     */
    public static RVP_VisualPublishResult publishDetonation(
            RVP_DetonationVisualContext context,
            List<RVP_VisualEffectData> visualEffectData) {
        if (context == null || visualEffectData == null || visualEffectData.isEmpty()) {
            return RVP_VisualPublishResult.NONE;
        }
        if (publisher == NO_OP) {
            return RVP_VisualPublishResult.NONE;
        }
        int publishedCount = 0;
        boolean suppressNative = false;
        for (RVP_VisualEffectData data : visualEffectData) {
            Optional<RVP_VisualEffectEvent> event = resolve(context, data);
            if (event.isEmpty()) {
                continue;
            }
            try {
                // 调用已安装的服务端发布端，把公共领域事件适配为一次 S2C 网络消息。
                publisher.publish(context.level(), event.get());
                publishedCount++;
                suppressNative |= data.isSuppressNativeExplosionEffect();
            } catch (RuntimeException exception) {
                LOGGER.warn("无法发布 RVP 爆炸视觉事件 {}，将保留本体普通爆炸视觉",
                        event.get().effectType(), exception);
            }
        }
        return publishedCount == 0
                ? RVP_VisualPublishResult.NONE
                : new RVP_VisualPublishResult(publishedCount, suppressNative);
    }

    private static Optional<RVP_VisualEffectEvent> resolve(
            RVP_DetonationVisualContext context,
            RVP_VisualEffectData data) {
        for (RVP_DetonationVisualResolver resolver : RESOLVERS) {
            try {
                if (resolver.supports(data)) {
                    // 调用公共解析器校验当前 schema，并生成不含客户端类型的不可变视觉事件。
                    return resolver.resolve(context, data);
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("RVP 爆炸视觉配置解析失败，将保留本体普通爆炸视觉", exception);
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
