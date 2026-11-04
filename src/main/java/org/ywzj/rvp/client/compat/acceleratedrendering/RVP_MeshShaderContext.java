package org.ywzj.rvp.client.compat.acceleratedrendering;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderStateShard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.ywzj.rvp.client.light.RVP_LightingCompatibility;
import java.lang.reflect.Method;

/** 保留光影的实体分类与调用方绘制目标；仅使用 Iris/Oculus 公开查询，不替换光影 shader。 */
final class RVP_MeshShaderContext implements AutoCloseable {
    /** 普通模式无需额外 GL 查询，也不产生第二份 GUI/世界缓存。 */
    private static final RVP_MeshShaderContext PLAIN = new RVP_MeshShaderContext(new Classification(false, 0, 0, 0), -1, -1);
    /** 可选公开桥的探测标记；失败时共享路径回退，不吞掉原模型。 */
    private static boolean probed;
    /** Iris 当前渲染分类单例；不持有会被光影重载销毁的 shader 或 FBO。 */
    private static Object state;
    /** 当前实体、方块实体和物品的材质分类查询。 */
    private static Method entity, blockEntity, item;
    /** Iris 自身实体材质包装，未知语义不旁路。 */
    private static Method wrapEntity;
    /** Iris 原有实体状态分片，保证使用光影包选定的实体 shader。 */
    private static Object entityShard;
    /** 只读分类参与几何缓存键；分类不同的模型不能借用首台车的扩展顶点属性。 */
    private final Classification classification;
    /** 进入调用时的读写 FBO；ExtendedShader.clear 会绑定主目标，退出时必须恢复。 */
    private final int drawFramebuffer, readFramebuffer;

    record Classification(boolean shaders, int entity, int blockEntity, int item) { }

    private RVP_MeshShaderContext(Classification classification, int drawFramebuffer, int readFramebuffer) {
        this.classification = classification;
        this.drawFramebuffer = drawFramebuffer;
        this.readFramebuffer = readFramebuffer;
    }

    static RVP_MeshShaderContext open() {
        // 调用项目现有光影状态桥，安装 Oculus 但未启用光影时保持普通缓存。
        if (!RVP_LightingCompatibility.shadersActive()) return PLAIN;
        probe();
        if (state == null) return null;
        try {
            Classification key = new Classification(true, ((Number) entity.invoke(state)).intValue(),
                    ((Number) blockEntity.invoke(state)).intValue(), ((Number) item.invoke(state)).intValue());
            return new RVP_MeshShaderContext(key, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
                    GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING));
        } catch (ReflectiveOperationException | LinkageError | ClassCastException failure) {
            return null;
        }
    }

    Classification classification() { return classification; }

    /** 通过实际消费者身份验证 Iris 的实体分类包装，不按 lambda 类名猜测或拆解私有字段。 */
    static RenderType entityMaterial(MultiBufferSource wrapped, MultiBufferSource expected, RenderType material) {
        probe();
        if (wrapEntity == null) return null;
        try {
            RenderType candidate = (RenderType) wrapEntity.invoke(null, "iris:is_entity", material, entityShard);
            return wrapped.getBuffer(material) == expected.getBuffer(candidate) ? candidate : null;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException failure) {
            return null;
        }
    }

    private static void probe() {
        if (probed) return;
        probed = true;
        for (String root : new String[]{"net.irisshaders.iris", "net.coderbot.iris"}) {
            try {
                Class<?> type = Class.forName(root + ".uniforms.CapturedRenderingState");
                Method e = type.getMethod("getCurrentRenderedEntity");
                Method b = type.getMethod("getCurrentRenderedBlockEntity");
                Method i = type.getMethod("getCurrentRenderedItem");
                Object instance = type.getField("INSTANCE").get(null);
                entity = e;
                blockEntity = b;
                item = i;
                state = instance;
                try {
                    Class<?> outer = Class.forName(root + ".layer.OuterWrappedRenderType");
                    wrapEntity = outer.getMethod("wrapExactlyOnce", String.class, RenderType.class, RenderStateShard.class);
                    entityShard = Class.forName(root + ".layer.EntityRenderStateShard").getField("INSTANCE").get(null);
                } catch (ReflectiveOperationException | LinkageError ignored) { wrapEntity = null; }
                return;
            } catch (ReflectiveOperationException | LinkageError ignored) { /* 未知接口继续原 AR/SBM 路径。 */ }
        }
    }

    @Override
    public void close() {
        if (drawFramebuffer < 0) return;
        // 经原版状态入口恢复调用方目标，避免阴影/热源复绘污染后续通道。
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
    }
}
