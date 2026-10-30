package org.ywzj.rvp.client.light;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.ywzj.rvp.client.particle.RVP_SmokeRenderState;

import java.nio.file.Path;

/** 隐藏窗口中复现提前爆炸烟的假阴影，执行真实烟雾深度规则、帧复制与正式光照程序。 */
public final class ExplosionSmokeDepthGpuRegression {
    /** 与共用像素回归一致的场景尺寸，单位像素。 */
    private static final int SIZE = 16;

    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) { throw new IllegalStateException("GLFW initialization"); }
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(32, 32, "RVP explosion smoke depth regression", 0, 0);
        if (window == 0) { throw new IllegalStateException("hidden OpenGL context"); }
        RVP_ScreenFrameResources frame = new RVP_ScreenFrameResources();
        try {
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities(); RenderSystem.initRenderThread();
            System.out.println("GPU=" + GL11.glGetString(GL11.GL_RENDERER));
            // 调用生产材质与帧资源，覆盖旧假阴影、提前/正常粒子顺序、烟自身受光和真实墙体遮挡。
            ScreenLightGpuRegression.testExplosionSmokeDepthCapture(Path.of(args[0]), () -> {
                // 模拟其它模组留下相反的真实状态；普通 RenderSystem 缓存命中无法纠正这种差异。
                RenderSystem.depthMask(false); GL11.glDepthMask(true);
                RenderSystem.enableDepthTest(); GL11.glDisable(GL11.GL_DEPTH_TEST);
                // 调用 MCHR 与白磷烟实际共用的深度入口，而不是在测试中重新实现修复。
                RVP_SmokeRenderState.applyDepth();
            }, (main, depth) -> {
                // 调用生产只读深度快照，不能用测试自己复制的纹理掩盖采集顺序问题。
                if (!frame.captureDepth(main, depth, SIZE, SIZE)) { throw new AssertionError("production depth capture failed"); }
                return frame.depth();
            });
            if (GL11.glGetError() != GL11.GL_NO_ERROR) { throw new AssertionError("OpenGL error"); }
            System.out.println("PASS explosion smoke lighting/no-shadow regression; OpenGL error=0");
        } finally {
            frame.release(); GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate();
        }
    }
}
