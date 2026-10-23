package org.ywzj.rvp.client.compat.distanthorizons;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** 手动 GPU 回归：隐藏窗口执行真实附件/深度复制类与项目 GLSL，不启动 Minecraft。 */
public final class DhFramebufferRegression {
    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW init failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long window = GLFW.glfwCreateWindow(32, 32, "RVP DH regression", 0, 0);
        if (window == 0) throw new IllegalStateException("hidden GL context failed");
        try {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("GPU=" + GL11.glGetString(GL11.GL_RENDERER));
            testRecreation();
            testCompositeShader(Path.of(args[0]));
            System.out.println("PASS all DH framebuffer and shader GPU regressions");
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    /** 覆盖放大、同尺寸重建、缩小，以及连续不重建时的正常挂接与资源所有权。 */
    private static void testRecreation() {
        int dh = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, dh);
        int depth = texture(true, 2);
        attach(GL30.GL_DEPTH_ATTACHMENT, depth);
        int color = texture(false, 2);
        attach(GL30.GL_COLOR_ATTACHMENT0, color);
        RVP_DhFramebufferSet rvp = new RVP_DhFramebufferSet();
        RVP_DhDepthCopy copy = new RVP_DhDepthCopy();
        // 调用真实 RVP 类建立旧对象引用，才能复现同名新对象与借用附件分离的问题。
        rvp.attachBorrowedTextures(color, depth);
        clear(1, 0, 1, .5);
        for (int size : new int[]{4, 4, 1}) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, dh);
            GL11.glDeleteTextures(depth);
            int nextDepth = texture(true, size);
            attach(GL30.GL_DEPTH_ATTACHMENT, nextDepth);
            GL11.glDeleteTextures(color);
            int nextColor = texture(false, size);
            attach(GL30.GL_COLOR_ATTACHMENT0, nextColor);
            clear(0, 0, 1, 1);
            if (nextDepth != depth || nextColor != color) {
                throw new AssertionError("Driver did not reuse names; this scenario was not exercised");
            }
            depth = nextDepth;
            color = nextColor;
            // 调用生产挂接逻辑，不在测试内代替它强制修复附件。
            rvp.attachBorrowedTextures(color, depth);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, rvp.compositeFramebuffer());
            clear(1, 0, 1, .5);
            assertPixel(dh, size - 1, size - 1, 255, 0, 255, .5F);
            // 调用生产深度复制类，验证复制源也更新到新对象，覆盖新尺寸的远角。
            copy.ensureSize(size, size);
            copy.copyFrom(rvp.depthSourceFramebuffer());
            int copyFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            assertDepth(copyFramebuffer, size - 1, size - 1, .5F);
            // 再次调用，验证不重建时不会清除或破坏借用内容。
            rvp.attachBorrowedTextures(color, depth);
            assertPixel(dh, 0, 0, 255, 0, 255, .5F);
            System.out.println("PASS recreated size=" + size + "x" + size + " reusedIDs=" + color + "/" + depth);
        }
        // 调用 RVP 释放入口，借用的 DH 纹理必须继续存活。
        rvp.release();
        copy.release();
        if (!GL11.glIsTexture(color) || !GL11.glIsTexture(depth)) {
            throw new AssertionError("RVP deleted borrowed textures");
        }
        GL30.glDeleteFramebuffers(dh);
        GL11.glDeleteTextures(color);
        GL11.glDeleteTextures(depth);
        System.out.println("PASS borrowed texture ownership");
    }

    /** 编译项目实际 shader，验证正常颜色、真实深度和地形遮挡，防止残留染色/0.5 探针。 */
    private static void testCompositeShader(Path root) throws Exception {
        Path shaders = root.resolve("src/main/resources/assets/ywzj_rvp/shaders/core");
        int vertex = compile(GL20.GL_VERTEX_SHADER, Files.readString(shaders.resolve("dh_depth_composite.vsh")));
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, Files.readString(shaders.resolve("dh_depth_composite.fsh")));
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragment);
        GL20.glBindAttribLocation(program, 0, "Position");
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            throw new AssertionError(GL20.glGetProgramInfoLog(program));
        }
        // 三个目标依次为源载具、DH 深度采样、合成输出；数组分别存 FBO/颜色/深度 ID。
        int[] source = target();
        clear(.25F, .5F, .75F, .25);
        int[] terrain = target();
        clear(0, 0, 0, 1);
        int[] output = target();
        clear(0, 0, 0, 1);
        GL20.glUseProgram(program);
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        for (String name : new String[]{"RvpInverseProjection", "DhInverseProjection", "DhProjection"}) {
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, name), false, identity);
        }
        uniform(program, "DhEmptyDepth", 1);
        uniform(program, "DhNearDepth", 0);
        uniform(program, "DhFarPlane", 100);
        uniform(program, "OcclusionBiasBlocks", 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "DiagnosticBypassOcclusion"), 0);
        String[] samplers = {"RvpColor", "RvpDepth", "DhDepthCopy"};
        int[] textures = {source[1], source[2], terrain[2]};
        for (int unit = 0; unit < textures.length; unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[unit]);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, samplers[unit]), unit);
        }
        int vao = GL30.glGenVertexArrays();
        int vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[]{-1, -1, 0, 3, -1, 0, -1, 3, 0}, GL15.GL_STATIC_DRAW);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
        GL11.glViewport(0, 0, 2, 2);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_ALWAYS);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        assertPixel(output[0], 1, 1, 64, 128, 191, .25F);
        System.out.println("PASS actual GLSL preserves color=64,128,191 and depth=0.25");

        // 可视探针必须只改变颜色，不能再像旧版一样将投影深度强制为 0.5。
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "DiagnosticLayerColorMode"), 1);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        assertPixel(output[0], 1, 1, 0, 255, 255, .25F);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "DiagnosticLayerColorMode"), 2);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        assertPixel(output[0], 1, 1, 255, 0, 255, .25F);
        System.out.println("PASS optional cyan/magenta probes preserve depth=0.25");

        // identity 逆投影下，载具视深度为 0.5、地形为 0.2，载具必须被地形遮挡。
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, terrain[0]);
        clear(0, 0, 0, .4);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, output[0]);
        clear(0, 0, 0, 1);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        assertPixel(output[0], 1, 1, 0, 0, 0, 1);
        System.out.println("PASS actual GLSL rejects occluded pixels even with tint enabled");
        for (int[] target : new int[][]{source, terrain, output}) {
            GL30.glDeleteFramebuffers(target[0]);
            GL11.glDeleteTextures(target[1]);
            GL11.glDeleteTextures(target[2]);
        }
        GL15.glDeleteBuffers(vbo);
        GL30.glDeleteVertexArrays(vao);
        GL20.glUseProgram(0);
        GL20.glDeleteProgram(program);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
    }

    /** 编译 GLSL 并将驱动错误作为测试失败。 */
    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new AssertionError(GL20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    /** 设置测试 shader 的浮点参数。 */
    private static void uniform(int program, String name, float value) {
        GL20.glUniform1f(GL20.glGetUniformLocation(program, name), value);
    }

    /** 新建独立 2×2 颜色/深度目标，返回 FBO、颜色、深度 ID。 */
    private static int[] target() {
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        int color = texture(false, 2);
        attach(GL30.GL_COLOR_ATTACHMENT0, color);
        int depth = texture(true, 2);
        attach(GL30.GL_DEPTH_ATTACHMENT, depth);
        return new int[]{fbo, color, depth};
    }

    /** 创建与实机格式一致的 RGBA8/DEPTH32F 纹理。 */
    private static int texture(boolean depth, int size) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, depth ? GL30.GL_DEPTH_COMPONENT32F : GL11.GL_RGBA8,
                size, size, 0, depth ? GL11.GL_DEPTH_COMPONENT : GL11.GL_RGBA,
                depth ? GL11.GL_FLOAT : GL11.GL_UNSIGNED_BYTE, 0L);
        return id;
    }

    /** 将纹理挂接到测试模拟的 DH 自有目标。 */
    private static void attach(int attachment, int texture) {
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, texture, 0);
    }

    /** 使用精确已知的颜色/深度写入目标。 */
    private static void clear(float r, float g, float b, double depth) {
        GL11.glClearColor(r, g, b, 1);
        GL11.glClearDepth(depth);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    /** 回读真实颜色并检查深度；不依赖 Java 侧 ID 相等推断写回成功。 */
    private static void assertPixel(int fbo, int x, int y, int r, int g, int b, float depth) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        try {
            pixel.putInt(0, 0x5a5a5a5a);
            GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
            // RGBA8 经清屏、采样、输出转换后允许一个量化台阶；青/品红染色仍会明确失败。
            if (Math.abs(Byte.toUnsignedInt(pixel.get(0)) - r) > 1
                    || Math.abs(Byte.toUnsignedInt(pixel.get(1)) - g) > 1
                    || Math.abs(Byte.toUnsignedInt(pixel.get(2)) - b) > 1
                    || Byte.toUnsignedInt(pixel.get(3)) != 255) {
                throw new AssertionError("Unexpected live framebuffer color at " + x + "," + y
                        + " rgba=" + Byte.toUnsignedInt(pixel.get(0)) + "," + Byte.toUnsignedInt(pixel.get(1))
                        + "," + Byte.toUnsignedInt(pixel.get(2)) + "," + Byte.toUnsignedInt(pixel.get(3))
                        + " glError=" + GL11.glGetError());
            }
            assertDepth(fbo, x, y, depth);
        } finally {
            MemoryUtil.memFree(pixel);
        }
    }

    /** 深度哨兵避免将超范围回读的旧内存误当有效数据。 */
    private static void assertDepth(int fbo, int x, int y, float expected) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        try {
            pixel.putFloat(0, Float.NaN);
            GL11.glReadPixels(x, y, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixel);
            if (pixel.getFloat(0) != expected || GL11.glGetError() != GL11.GL_NO_ERROR) {
                throw new AssertionError("Unexpected live depth=" + pixel.getFloat(0) + " expected=" + expected);
            }
        } finally {
            MemoryUtil.memFree(pixel);
        }
    }
}
