package org.ywzj.rvp.client.visual;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 物理客户端视觉分派器，负责工厂分派和实例生命周期。 */
public final class RVP_ClientVisualEffectDispatcher {
    /** 日志记录器。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 同时活动的视觉实例硬上限。 */
    private static final int MAX_ACTIVE_EFFECTS = 24;
    /** 效果类型到客户端工厂的注册表。 */
    private static final Map<ResourceLocation, RVP_ClientVisualEffectFactory> FACTORIES = new ConcurrentHashMap<>();
    /** 已记录未知类型警告的集合，避免连续爆炸刷屏。 */
    private static final Set<ResourceLocation> WARNED_UNKNOWN_TYPES = ConcurrentHashMap.newKeySet();
    /** 当前世界内仍在运行的视觉实例。 */
    private static final List<RVP_ClientVisualEffect> ACTIVE_EFFECTS = new ArrayList<>();
    /** 活动实例所属的客户端世界，用于阻止实例跨世界残留。 */
    private static ClientLevel activeLevel;

    private RVP_ClientVisualEffectDispatcher() {
    }

    public static void register(ResourceLocation effectType, RVP_ClientVisualEffectFactory factory) {
        if (FACTORIES.putIfAbsent(effectType, factory) != null) {
            throw new IllegalStateException("RVP visual effect factory is already registered: " + effectType);
        }
    }

    public static void accept(RVP_VisualEffectEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !level.dimension().equals(event.dimension())) {
            return;
        }
        ensureLevel(level);
        RVP_ClientVisualEffectFactory factory = FACTORIES.get(event.effectType());
        if (factory == null) {
            if (WARNED_UNKNOWN_TYPES.add(event.effectType())) {
                LOGGER.warn("客户端未注册 RVP 视觉效果类型 {}，已安全忽略该事件", event.effectType());
            }
            return;
        }
        // 调用已注册的 RVP 客户端工厂，把公共领域事件解析成具体效果实例。
        Optional<RVP_ClientVisualEffect> created = factory.create(level, event);
        if (created.isEmpty()) {
            return;
        }
        if (ACTIVE_EFFECTS.size() >= MAX_ACTIVE_EFFECTS) {
            // 调用效果生命周期接口清理最旧实例，保证连续引爆不会突破全局实例预算。
            ACTIVE_EFFECTS.remove(0).close();
        }
        ACTIVE_EFFECTS.add(created.get());
    }

    /** 推进当前世界的全部视觉实例。 */
    public static void tick(ClientLevel level) {
        ensureLevel(level);
        for (int index = ACTIVE_EFFECTS.size() - 1; index >= 0; index--) {
            RVP_ClientVisualEffect effect = ACTIVE_EFFECTS.get(index);
            // 调用具体 RVP 视觉实例推进本地确定性模拟。
            effect.tick();
            if (effect.isFinished()) {
                // 调用效果生命周期接口释放已经结束的实例资源。
                effect.close();
                ACTIVE_EFFECTS.remove(index);
            }
        }
    }

    /** 渲染当前世界的全部视觉实例。 */
    public static void render(RenderLevelStageEvent event) {
        for (RVP_ClientVisualEffect effect : ACTIVE_EFFECTS) {
            // 调用具体 RVP 视觉实例提交只读渲染状态，不在渲染阶段推进 tick。
            effect.render(event);
        }
    }

    /**
     * 把全部自绘几何类视觉实例重画进本体热成像 thermal_buffer（仅热成像激活时由
     * {@code RVP_ThermalParticleChannel} 在 AFTER_PARTICLES 热成像窗口调用）。
     * 粒子类特效默认空实现自然跳过（其热成像由通道的粒子路径覆盖），不重复提亮。
     */
    public static void renderThermal(RenderLevelStageEvent event) {
        for (RVP_ClientVisualEffect effect : ACTIVE_EFFECTS) {
            effect.renderThermal(event);
        }
    }

    /** 清理当前世界的全部视觉实例。 */
    public static void clear() {
        for (RVP_ClientVisualEffect effect : ACTIVE_EFFECTS) {
            // 调用效果生命周期接口释放切换世界前仍存活的实例资源。
            effect.close();
        }
        ACTIVE_EFFECTS.clear();
        activeLevel = null;
    }

    private static void ensureLevel(ClientLevel level) {
        if (activeLevel == level) {
            return;
        }
        clear();
        activeLevel = level;
    }
}
