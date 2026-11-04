package org.ywzj.rvp.client.compat.thermal;

import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL20;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Function;

/** 复用 Embeddium 原版地形 shader 源码，按现有 Iris VAO 的 1～4 属性位置链接一次。 */
final class RVP_EmbeddiumThermalPrograms {
    /** 原渲染器已有的程序表与顶点类型；新程序的释放仍由原渲染器负责。 */
    private final Field cache, vertexType;
    /** 原版地形通道、雾模式与 shader 阶段。 */
    private final Object[] passes;
    private final Object fog, vertex, fragment;
    /** 仅探测一次的 Embeddium 公开构造与链接接口。 */
    private final Constructor<?> options, shaderInterface;
    private final Method constants, loadShader, builder, attach, attribute, output, link, deleteShader, handle;
    /** 最近验证过的原渲染器，弱引用不延长其 GPU 生命周期，稳态不再查询 OpenGL。 */
    private java.lang.ref.WeakReference<Object> prepared = new java.lang.ref.WeakReference<>(null);

    @SuppressWarnings({"unchecked", "rawtypes"})
    RVP_EmbeddiumThermalPrograms() throws ReflectiveOperationException {
        Class<?> renderer = Class.forName("me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer");
        cache = renderer.getDeclaredField("programs");cache.setAccessible(true);
        vertexType = renderer.getDeclaredField("vertexType");vertexType.setAccessible(true);
        passes = (Object[]) Class.forName("me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses").getField("ALL").get(null);
        Class<?> fogType = Class.forName("me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkFogMode");
        fog = Enum.valueOf((Class) fogType, "SMOOTH");
        Class<?> optionType = Class.forName("me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions");
        options = optionType.getConstructor(fogType, passes[0].getClass(), vertexType.getType());
        constants = optionType.getMethod("constants");
        Class<?> shaderType = Class.forName("me.jellysquid.mods.sodium.client.gl.shader.ShaderType");
        vertex = Enum.valueOf((Class) shaderType, "VERTEX");fragment = Enum.valueOf((Class) shaderType, "FRAGMENT");
        loadShader = Class.forName("me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader").getMethod("loadShader",
                shaderType, ResourceLocation.class, constants.getReturnType());
        Class<?> program = Class.forName("me.jellysquid.mods.sodium.client.gl.shader.GlProgram");
        builder = program.getMethod("builder", ResourceLocation.class);
        handle = program.getMethod("handle");
        Class<?> programBuilder = builder.getReturnType();
        Class<?> shader = loadShader.getReturnType();
        attach = programBuilder.getMethod("attachShader", shader);
        attribute = programBuilder.getMethod("bindAttribute", String.class, int.class);
        output = programBuilder.getMethod("bindFragmentData", String.class, int.class);
        link = programBuilder.getMethod("link", Function.class);
        deleteShader = shader.getMethod("delete");
        shaderInterface = Class.forName("me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface")
                .getConstructor(Class.forName("me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext"), optionType);
    }

    @SuppressWarnings("unchecked")
    void prepare(Object renderer) throws ReflectiveOperationException {
        if (prepared.get() == renderer) return;
        Map<Object, Object> programs = (Map<Object, Object>) cache.get(renderer);
        if (!programs.isEmpty()) {
            // 不覆盖其它模组已经创建的程序；已准备的热成像程序只做轻量身份校验。
            for (Object program : programs.values()) {
                if (GL20.glGetAttribLocation(((Number) handle.invoke(program)).intValue(), "a_PosId") != 1) {
                    throw new IllegalStateException("未知的原版地形属性绑定");
                }
            }
            prepared = new java.lang.ref.WeakReference<>(renderer);
            return;
        }
        for (Object pass : passes) {
            // 调用原选项生成器，保留现有 XHFP 压缩尺度与地形颜色编码。
            Object option = options.newInstance(fog, pass, vertexType.get(renderer));
            Object defines = constants.invoke(option);
            Object vert = loadShader.invoke(null, vertex, new ResourceLocation("sodium", "blocks/block_layer_opaque.vsh"), defines);
            Object frag = null;
            try {
                frag = loadShader.invoke(null, fragment, new ResourceLocation("sodium", "blocks/block_layer_opaque.fsh"), defines);
                Object build = builder.invoke(null, new ResourceLocation("ywzj_rvp", "thermal_terrain"));
                attach.invoke(build, vert);attach.invoke(build, frag);
                attribute.invoke(build, "a_PosId", 1);attribute.invoke(build, "a_Color", 2);
                attribute.invoke(build, "a_TexCoord", 3);attribute.invoke(build, "a_LightCoord", 4);
                output.invoke(build, "fragColor", 0);
                Function<Object, Object> bind = context -> {
                    try { return shaderInterface.newInstance(context, option); }
                    catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                };
                programs.put(option, link.invoke(build, bind));
            } finally {
                // 原版 GlShader 临时对象在链接后释放；程序本体留在原渲染器表中由其统一释放。
                if (frag != null) deleteShader.invoke(frag);
                deleteShader.invoke(vert);
            }
        }
        prepared = new java.lang.ref.WeakReference<>(renderer);
    }
}
