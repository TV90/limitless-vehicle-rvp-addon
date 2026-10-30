package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.shaders.BlendMode;
import com.mojang.blaze3d.systems.RenderSystem;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * 独立保存 Shader/Effect 共用的 BlendMode 调用历史，不把实际 GL 混合开关误当作该历史。
 *
 * <p>原版 RenderType 可以在 Shader.apply 之外开关混合，因此两者刻意可能不同。
 * RVP 的默认不透明 Shader 会改变这份全局历史；如果只恢复 GL，接着执行的本体过载滤镜
 * 就可能重新启用 Alpha 混合，把天空/DH 中颜色有效但 Alpha 为零的像素混成黑色。</p>
 *
 * <p>通过字段类型定位 Minecraft 1.20.1 唯一的可写静态 BlendMode 缓存，避免开发名和发行名差异。
 * 不注入、不调用 BlendMode.apply，也不修改本体资源；退出时只原样归还进入时的对象引用。</p>
 */
public final class RVP_BlendModeState {
    /** 引擎唯一的可写静态 BlendMode 字段；字段对象只解析一次，不持有世界或 Shader 实例。 */
    private static final Field LAST_APPLIED = findLastApplied();
    /** 进入作用域前的真实历史对象；首次绘制前为 null，也必须原样恢复。 */
    private final BlendMode previous;

    private RVP_BlendModeState(BlendMode previous) { this.previous = previous; }

    /** 在任何 RVP Shader/Effect.apply 之前捕获，不能由 glIsEnabled(GL_BLEND) 反推。 */
    public static RVP_BlendModeState capture() {
        RenderSystem.assertOnRenderThread();
        try { return new RVP_BlendModeState((BlendMode) LAST_APPLIED.get(null)); }
        catch (IllegalAccessException exception) { throw new IllegalStateException("无法读取 Minecraft 混合调用历史", exception); }
    }

    /** 仅还原 Java 历史引用，不申请混合模式、不改变显卡状态，支持嵌套作用域及 null 历史。 */
    public void restore() {
        RenderSystem.assertOnRenderThread();
        try { LAST_APPLIED.set(null, previous); }
        catch (IllegalAccessException exception) { throw new IllegalStateException("无法恢复 Minecraft 混合调用历史", exception); }
    }

    /** 按类型与可写静态属性唯一定位缓存，兼容开发环境 lastApplied 和发行环境的混淆字段名。 */
    private static Field findLastApplied() {
        Field result = null;
        for (Field field : BlendMode.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != BlendMode.class || !Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) { continue; }
            if (result != null) { throw new IllegalStateException("Minecraft BlendMode 存在多个可写静态缓存，不能猜测恢复目标"); }
            result = field;
        }
        if (result == null) { throw new IllegalStateException("Minecraft BlendMode 调用历史字段不存在"); }
        result.setAccessible(true);
        return result;
    }
}
