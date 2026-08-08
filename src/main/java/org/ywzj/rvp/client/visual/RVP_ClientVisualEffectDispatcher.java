package org.ywzj.rvp.client.visual;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 物理客户端视觉分派器；阶段 A 只提供安全注册入口，不创建任何粒子或渲染对象。 */
public final class RVP_ClientVisualEffectDispatcher {
    /** 日志记录器。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 效果类型到客户端工厂的注册表。 */
    private static final Map<ResourceLocation, RVP_ClientVisualEffectFactory> FACTORIES = new ConcurrentHashMap<>();
    /** 已记录未知类型警告的集合，避免连续爆炸刷屏。 */
    private static final Set<ResourceLocation> WARNED_UNKNOWN_TYPES = ConcurrentHashMap.newKeySet();

    private RVP_ClientVisualEffectDispatcher() {
    }

    public static void register(ResourceLocation effectType, RVP_ClientVisualEffectFactory factory) {
        if (FACTORIES.putIfAbsent(effectType, factory) != null) {
            throw new IllegalStateException("RVP visual effect factory is already registered: " + effectType);
        }
    }

    public static void accept(RVP_VisualEffectEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().equals(event.dimension())) {
            return;
        }
        RVP_ClientVisualEffectFactory factory = FACTORIES.get(event.effectType());
        if (factory == null) {
            if (WARNED_UNKNOWN_TYPES.add(event.effectType())) {
                LOGGER.warn("客户端未注册 RVP 视觉效果类型 {}，已安全忽略该事件", event.effectType());
            }
            return;
        }
        // 调用已注册的客户端工厂，根据公共领域事件创建具体视觉实例。
        factory.create(event);
    }
}
