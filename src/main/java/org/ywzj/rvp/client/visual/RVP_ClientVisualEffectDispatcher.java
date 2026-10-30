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
    /** 威尔逊云复杂效果同时最多四个。 */
    private static final int MAX_COMPLEX_EFFECTS = 4;
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
        if (created.get() instanceof RVP_WilsonMachEffectInstance) {
            long count = ACTIVE_EFFECTS.stream().filter(RVP_ClientVisualEffectDispatcher::isComplex).count();
            if (count >= MAX_COMPLEX_EFFECTS) {
                for (int index = 0; index < ACTIVE_EFFECTS.size(); index++) {
                    if (isComplex(ACTIVE_EFFECTS.get(index))) {
                        // 调用关闭入口，同时取消最旧复杂效果的云团、球壳与延迟音。
                        ACTIVE_EFFECTS.remove(index).close();
                        break;
                    }
                }
            }
        }
        // 默认工厂已在自身实例管理光；其它显式 flash 事件通过组合附加，自动压力波 flash=false 不重复点亮。
        ACTIVE_EFFECTS.add(event.flash() && !RVP_DefaultExplosionEffectFactory.EFFECT_TYPE.equals(event.effectType())
                ? new RVP_LitVisualEffect(level, event, created.get()) : created.get());
    }

    /** 包装后仍按原实例复杂度计数，不改变既有粒子/云团预算。 */
    private static boolean isComplex(RVP_ClientVisualEffect effect) {
        return effect instanceof RVP_WilsonMachEffectInstance
                || effect instanceof RVP_LitVisualEffect lit && lit.isComplex();
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

    /** 把相机反馈分发给实例，避免渲染时直接改玩家状态。 */
    public static void applyCameraShake(net.minecraftforge.client.event.ViewportEvent.ComputeCameraAngles event) {
        for (RVP_ClientVisualEffect effect : ACTIVE_EFFECTS) {
            // 调用实例反馈钩子，统一遵守生命周期与淘汰结果。
            effect.applyCameraShake(event);
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
