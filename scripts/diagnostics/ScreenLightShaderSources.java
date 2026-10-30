package org.ywzj.rvp.client.light;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.*;

/** 原生 OpenGL 诊断展开生产 #moj_import；游戏端仍由 Minecraft 自己预处理，不复制算法。 */
final class ScreenLightShaderSources {
    /** 当前生产使用的带命名空间包含语句；拒绝未展开语句，避免悄悄漏测公共核心。 */
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*#moj_import\\s+<([a-z0-9_.-]+):([^>]+)>\\s*$");
    /** 原生诊断中有效的空整数纹理；禁用几何时也不能让不同类型采样器共用单元0。 */
    private static int emptyCells;
    /** 原生诊断中有效的空碰撞盒表。 */
    private static int emptyPalette;

    private ScreenLightShaderSources() { }

    /** 原生程序没有 ShaderInstance 绑定器，显式提供六个采样器中新增的两张输入。 */
    static void bindEmptyGeometry(int program) {
        int location = GL20.glGetUniformLocation(program, "GeometryCells");
        if (location < 0) { return; }
        int previous = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        if (emptyCells == 0) {
            emptyCells = GL11.glGenTextures(); GL13.glActiveTexture(GL13.GL_TEXTURE4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, emptyCells);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R16UI, 1, 1, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT, new short[] {0});
            emptyPalette = GL11.glGenTextures(); GL13.glActiveTexture(GL13.GL_TEXTURE5);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, emptyPalette);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 1, 2, 0, GL11.GL_RGBA, GL11.GL_FLOAT, new float[8]);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE4); GL11.glBindTexture(GL11.GL_TEXTURE_2D, emptyCells);
        GL20.glUniform1i(location, 4);
        GL13.glActiveTexture(GL13.GL_TEXTURE5); GL11.glBindTexture(GL11.GL_TEXTURE_2D, emptyPalette);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "GeometryPalette"), 5);
        GL13.glActiveTexture(previous);
    }

    /** 与游戏资源目录同样解析命名空间，仅读取源码资源。 */
    static String read(Path file) throws IOException {
        Path assets = file.toAbsolutePath().getParent().getParent().getParent().getParent();
        // 调用带循环检测的展开，实际编译生产公共文件，不能用测试字符串替代算法。
        return expand(file.toAbsolutePath(), assets, new HashSet<>());
    }

    /** 递归展开并约束包含目标在 assets 内；循环或未知语法必须报错。 */
    private static String expand(Path file, Path assets, Set<Path> active) throws IOException {
        file = file.normalize();
        if (!file.startsWith(assets.normalize()) || !active.add(file)) {
            throw new IOException("Invalid or cyclic shader include: " + file);
        }
        String source = Files.readString(file);
        Matcher matcher = IMPORT.matcher(source);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            Path include = assets.resolve(matcher.group(1)).resolve("shaders/include").resolve(matcher.group(2));
            // 调用实际包含文件读取；替换只发生在内存，不写回资源。
            matcher.appendReplacement(output, Matcher.quoteReplacement(expand(include, assets, active)));
        }
        matcher.appendTail(output);
        active.remove(file);
        if (output.indexOf("#moj_import") >= 0) { throw new IOException("Unsupported shader include: " + file); }
        return output.toString();
    }
}
