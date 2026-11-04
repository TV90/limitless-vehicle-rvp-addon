package org.ywzj.rvp.client.compat.thermal;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/** Oculus 1.8 / Embeddium 临时管线桥；通过组合保留原缓存、后台地形任务和重载所有权。 */
public final class RVP_OculusThermalBridge implements RVP_ThermalShaderPause.Bridge {
    /** 原管理器外的轻量组合层，不拥有其 BSL/FBO。 */
    private final RVP_ThermalPipelineManager manager;
    /** Embeddium 当前渲染器与 Oculus 地形覆盖表的已核对接口。 */
    private final Method sodiumQuery, deleteProgram;
    private final Field sections, renderer, overrides, programs, created, programGeneration;
    /** 原版地形程序按当前扩展 VAO 链接，保留已有区块网格。 */
    private final RVP_EmbeddiumThermalPrograms terrain;
    /** 无光影的小网格缓冲，按主体绘制调用提交，不跨帧保留顶点。 */
    private final RVP_ThermalFallbackBuffer fallback = new RVP_ThermalFallbackBuffer();

    public RVP_OculusThermalBridge() throws ReflectiveOperationException {
        Class<?> sodium = Class.forName("me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer");
        sodiumQuery = sodium.getMethod("instanceNullable");
        sections = field(sodium, "renderSectionManager");
        renderer = field(Class.forName("me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager"), "chunkRenderer");
        overrides = field(Class.forName("me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer"), "irisChunkProgramOverrides");
        Class<?> owner = Class.forName("net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisChunkProgramOverrides");
        programs = field(owner, "programs");created = field(owner, "shadersCreated");
        programGeneration = field(owner, "versionCounterForSodiumShaderReload");
        deleteProgram = Class.forName("me.jellysquid.mods.sodium.client.gl.shader.GlProgram").getMethod("delete");
        terrain = new RVP_EmbeddiumThermalPrograms();
        PipelineManager original = Iris.getPipelineManager();
        if (original.getClass() != PipelineManager.class) throw new IllegalStateException("已有其它管线管理器包装");
        Field managerField = field(Iris.class, "pipelineManager");
        // 两个原版基类构造器会重设地形设置；只在标题界面执行，并在 finally 恢复全部字段。
        Class<?> settingsType = Class.forName("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings");
        Object settings = settingsType.getField("INSTANCE").get(null);
        Map<Field, Object> saved = new HashMap<>();
        for (Field f : settingsType.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) { f.setAccessible(true);saved.put(f, f.get(settings)); }
        }
        try { manager = new RVP_ThermalPipelineManager(original, new VanillaRenderingPipeline()); }
        finally { for (var entry : saved.entrySet()) entry.getKey().set(settings, entry.getValue()); }
        managerField.set(null, manager);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);f.setAccessible(true);return f;
    }

    private Object currentRenderer() throws ReflectiveOperationException {
        Object worldRenderer = sodiumQuery.invoke(null);
        Object sectionManager = worldRenderer == null ? null : sections.get(worldRenderer);
        return sectionManager == null ? null : renderer.get(sectionManager);
    }

    @Override
    public RVP_ThermalShaderPause.Fallback fallback(net.minecraft.client.renderer.MultiBufferSource source) {
        return manager.isBorrowing() ? fallback.open() : new RVP_ThermalShaderPause.Fallback() {
            @Override public net.minecraft.client.renderer.MultiBufferSource source() { return source; }
            @Override public void close() { }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public AutoCloseable pause() throws ReflectiveOperationException {
        if (Iris.getPipelineManager() != manager) return null;
        WorldRenderingPipeline original = manager.originalPipeline();
        Object chunkRenderer = currentRenderer();
        if (!(original instanceof IrisRenderingPipeline) || chunkRenderer == null) return null;
        Object owner = overrides.get(chunkRenderer);
        int serial = manager.getVersionCounterForSodiumShaderReload();
        int oldProgramGeneration = programGeneration.getInt(owner);
        boolean oldCreated = created.getBoolean(owner);
        // 在原状态下生成与后台区块相同的颜色格式；首次创建后保留在 Embeddium 原程序表中。
        terrain.prepare(chunkRenderer);
        Map<Object, Object> table = (Map<Object, Object>) programs.get(owner);
        RVP_RetainedPrograms retained = new RVP_RetainedPrograms(table, program -> {
            try { deleteProgram.invoke(program); }
            catch (ReflectiveOperationException ex) { throw new IllegalStateException("无法释放重载前的地形程序", ex); }
        });
        AutoCloseable lease = () -> {
            try {
                boolean sameOwner = manager.getVersionCounterForSodiumShaderReload() == serial
                        && currentRenderer() == chunkRenderer;
                if (retained.restore(sameOwner)) {
                    programGeneration.setInt(owner, oldProgramGeneration);created.setBoolean(owner, oldCreated);
                }
            } finally { manager.end(); }
        };
        try {
            programGeneration.setInt(owner, serial);created.setBoolean(owner, true);
            manager.begin(original);
            return lease;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            try { lease.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
