package org.ywzj.rvp.client.light;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderStateShard;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.nio.file.Path;

/** 使用真实 Minecraft 深度状态与生产光照 GLSL 验证“尾焰写深度→喷口被自身挡掉”。 */
public final class MotorFlameDepthGpuRegression {
    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) { throw new IllegalStateException("GLFW initialization"); }
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(32, 32, "RVP motor flame depth regression", 0, 0);
        if (window == 0) { throw new IllegalStateException("hidden OpenGL context"); }
        try {
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            System.out.println("GPU=" + GL11.glGetString(GL11.GL_RENDERER));
            // 只执行真实状态片段，避开完整 RenderType 初始化所需的 Forge 启动变换与游戏注册表。
            // 旧 entityTranslucent 使用默认 COLOR_DEPTH_WRITE；修复使用 COLOR_WRITE，并保留 LEQUAL。
            RenderStateShard legacyMask = state("COLOR_DEPTH_WRITE");
            RenderStateShard fixedMask = state("COLOR_WRITE");
            RenderStateShard fixedDepthTest = state("LEQUAL_DEPTH_TEST");
            // 调用共享显卡回读，真实掩码分别送入旧故障、修复、前景墙三种完整 Shader 链路。
            ScreenLightGpuRegression.testMotorFlameDepthCapture(Path.of(args[0]),
                    () -> applyMask(legacyMask), () -> applyMask(fixedMask), () -> {
                        RenderSystem.disableDepthTest(); fixedDepthTest.setupRenderState();
                    });
            if (GL11.glGetError() != GL11.GL_NO_ERROR) { throw new AssertionError("OpenGL error"); }
            System.out.println("PASS motor flame self-occlusion regression; OpenGL error=0");
        } finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
    }

    /** 对齐引擎缓存后执行真实写掩码；共享 GLSL 测试的原生 GL 操作不会伪造结果。 */
    private static void applyMask(RenderStateShard mask) {
        RenderSystem.depthMask(false); RenderSystem.depthMask(true); mask.setupRenderState();
    }

    /** 仅诊断程序反射读取原版状态常量，不修改生产字段、不增加 Mixin 或访问器。 */
    private static RenderStateShard state(String name) throws ReflectiveOperationException {
        Field shardField = RenderStateShard.class.getDeclaredField(name);
        shardField.setAccessible(true);
        return (RenderStateShard) shardField.get(null);
    }
}
