package org.ywzj.rvp.client.light;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.ywzj.rvp.client.render.RVP_GlStateSync;

/** 正式光路的显式统计及历史深度算法回归；实际投影已由 RVP_LightGeometryPass 提供三维几何。 */
final class RVP_LightOcclusionPass {
    /** 单条光路最多查询的格数，分层跳过空区域后才细查可能命中的像素。 */
    static final int MAX_QUERIES = 512;
    /** 保存各层最近/最远观察空间正距离的 RG32F 纹理。 */
    private static int ranges;
    /** 归约临时纹理；读取 ranges、写入 scratch，禁止纹理附件读写反馈。 */
    private static int scratch;
    /** 分层纹理的自有绘制目标，只在构建第零层时写入。 */
    private static int rangeTarget;
    /** 归约时的自有绘制目标，每次写临时纹理再复制到 ranges 的下一层。 */
    private static int scratchTarget;
    /** 补齐到二次幂的纹理宽度，保证奇数窗口最右列也能进入最高层。 */
    private static int paddedWidth;
    /** 补齐到二次幂的纹理高度，补齐区域标记为空，不能生成假遮挡。 */
    private static int paddedHeight;
    /** 当前纹理的最高层编号，零层是一像素一格。 */
    private static int maxLevel;
    /** 是否属于本显示帧的完整输入，帧开始、异常和世界清理均立即失效。 */
    private static boolean ready;
    /** 历史回归的分配失败标志，不影响正式三维路径的选择。 */
    private static boolean available = true;
    /** 用户显式请求下一次有灯画面的像素统计，正常绘制不回读 GPU。 */
    private static boolean reportRequested;
    /** 最近一次显卡回读摘要，描述其样本范围，不能冒充连续每帧统计。 */
    private static String lastReport = "尚未采样；/rvplight occlusion 采样下一帧表面光路";

    private RVP_LightOcclusionPass() { }

    /** 新显示帧先失效；持续使用同一显存分配，不使用旧画面的深度。 */
    static void beginFrame() { ready = false; }

    /** 历史深度路径回归读取真实准备状态，不解析给玩家看的诊断文案。 */
    static boolean isReady() { return ready; }

    /** 仅历史回归生成完整深度层级；正式渲染不再调用，避免将单层相机轮廓继续当作实体体积。 */
    static void prepare(RVP_ScreenFrameResources frame, ShaderInstance shader) {
        ready = false;
        if (!available) {
            // 调用参数绑定，用本帧原始深度做逐像素有界检查，不采样失败的层级。
            upload(shader, frame.depth());
            return;
        }
        try {
            // 调用尺寸复用，窗口缩放时重新分配；其它帧只更新内容。
            ensureSize(frame.width(), frame.height());
            shader.safeGetUniform("OcclusionReady").set(0);
            shader.setSampler("OcclusionDepth", frame.depth());
            shader.safeGetUniform("OutputMode").set(5);
            frame.bind(rangeTarget, paddedWidth, paddedHeight);
            frame.draw(shader);
            for (int level = 1; level <= maxLevel; level++) {
                int width = Math.max(1, paddedWidth >> level);
                int height = Math.max(1, paddedHeight >> level);
                shader.setSampler("OcclusionDepth", ranges);
                shader.safeGetUniform("OcclusionReduceLevel").set(level - 1);
                shader.safeGetUniform("OutputMode").set(6);
                // 调用独立临时目标；不能边采样 ranges 边把它挂为当前绘制附件。
                frame.bind(scratchTarget, width, height);
                frame.draw(shader);
                RVP_GlStateSync.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, scratchTarget);
                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                RVP_GlStateSync.activeTexture(GL13.GL_TEXTURE3);
                RVP_GlStateSync.bindTexture2D(ranges);
                GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, level, 0, 0, 0, 0, width, height);
            }
            ready = true;
        } catch (RuntimeException exception) {
            // 调用自有显存释放，异常只降级此优化，不让旧深度参与本帧遮挡。
            release();
            available = false;
            lastReport = "分层深度不可用，使用逐像素有界检查：" + exception.getClass().getSimpleName();
        }
        // 调用统一绑定，屏幕光及稍后的烟雾读取完全相同的当帧层级。
        upload(shader, frame.depth());
    }

    /** 所有使用遮挡核心的材质都显式设置有效采样器与预算，资源重载不遗留旧 uniform。 */
    static void upload(ShaderInstance shader, int fallback) {
        shader.setSampler("OcclusionDepth", ready ? ranges : fallback);
        shader.safeGetUniform("OcclusionReady").set(ready ? 1 : 0);
        shader.safeGetUniform("OcclusionMaxLevel").set(ready ? maxLevel : 0);
        shader.safeGetUniform("OcclusionMaxSteps").set(MAX_QUERIES);
    }

    /** 只登记请求，不在命令线程直接操作显卡；结果供下一次 status 查看。 */
    static void requestReport() {
        reportRequested = true;
        lastReport = "等待下一帧有环境光的表面采样；该诊断会执行一次额外绘制和像素回读";
    }

    /** 仅显式请求时运行，统计表面光路的命中/离屏/预算耗尽，绝不每帧同步 GPU。 */
    static void captureReport(RVP_ScreenFrameResources frame, ShaderInstance shader) {
        if (!reportRequested) { return; }
        reportRequested = false;
        shader.safeGetUniform("OutputMode").set(4);
        // 调用已经完成显示合成的临时光照目标，诊断结果不画到用户画面。
        frame.bind(frame.lightTarget(), frame.width(), frame.height());
        frame.draw(shader);
        RVP_GlStateSync.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, frame.lightTarget());
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        float[] pixels = new float[frame.width() * frame.height() * 4];
        GL11.glReadPixels(0, 0, frame.width(), frame.height(), GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
        long hits = 0;
        long offscreen = 0;
        long exhausted = 0;
        int queries = 0;
        for (int index = 0; index < pixels.length; index += 4) {
            hits += (long) pixels[index];
            offscreen += (long) pixels[index + 1];
            exhausted += (long) pixels[index + 2];
            queries = Math.max(queries, (int) pixels[index + 3]);
        }
        lastReport = "最近单帧表面采样 " + frame.width() + "×" + frame.height()
                + "：命中光路=" + hits + "，离屏或几何范围外=" + offscreen + "，预算耗尽=" + exhausted
                + "，最大查询=" + queries + "/" + MAX_QUERIES;
    }

    /** 只读状态包含预算耗尽的实际处理规则，避免把降级误报为几何验收通过。 */
    static String status() {
        return "光路诊断；范围外保留照亮；" + lastReport;
    }

    /** 只在尺寸变化时分配；归约临时纹理只需要最高一级输出的尺寸。 */
    private static void ensureSize(int width, int height) {
        int targetWidth = Math.max(1, Integer.highestOneBit(width - 1) << 1);
        int targetHeight = Math.max(1, Integer.highestOneBit(height - 1) << 1);
        if (ranges != 0 && targetWidth == paddedWidth && targetHeight == paddedHeight) { return; }
        // 调用清理时保留等待采样请求，窗口变化不能悄悄吞掉用户诊断。
        boolean pending = reportRequested;
        release();
        reportRequested = pending;
        paddedWidth = targetWidth;
        paddedHeight = targetHeight;
        maxLevel = 31 - Integer.numberOfLeadingZeros(Math.max(targetWidth, targetHeight));
        // 调用自有纹理申请：每层都显式分配，不能依赖会计算平均值的 glGenerateMipmap。
        ranges = texture(targetWidth, targetHeight, maxLevel);
        scratch = texture(Math.max(1, targetWidth / 2), Math.max(1, targetHeight / 2), 0);
        rangeTarget = framebuffer(ranges);
        scratchTarget = framebuffer(scratch);
    }

    /** RG32F 保存有单位的最近/最远距离，避免半精度远景丢失细小深度变化。 */
    private static int texture(int width, int height, int levels) {
        int texture = GL11.glGenTextures();
        RVP_GlStateSync.activeTexture(GL13.GL_TEXTURE3);
        RVP_GlStateSync.bindTexture2D(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels);
        for (int level = 0; level <= levels; level++) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, level, GL30.GL_RG32F,
                    Math.max(1, width >> level), Math.max(1, height >> level), 0,
                    GL30.GL_RG, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
        }
        return texture;
    }

    /** 只创建本系统的附件；不修改原版、DH 或光影拥有的帧缓冲。 */
    private static int framebuffer(int texture) {
        int target = GL30.glGenFramebuffers();
        RVP_GlStateSync.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glDeleteFramebuffers(target);
            throw new IllegalStateException("RVP occlusion range framebuffer incomplete");
        }
        return target;
    }

    /** 世界卸载、窗口变化和资源重载都释放自有显存；这里不删除任何磁盘文件。 */
    static void release() {
        ready = false;
        if (ranges != 0) { RenderSystem.deleteTexture(ranges); ranges = 0; }
        if (scratch != 0) { RenderSystem.deleteTexture(scratch); scratch = 0; }
        if (rangeTarget != 0) { GL30.glDeleteFramebuffers(rangeTarget); rangeTarget = 0; }
        if (scratchTarget != 0) { GL30.glDeleteFramebuffers(scratchTarget); scratchTarget = 0; }
        paddedWidth = 0; paddedHeight = 0; maxLevel = 0;
        available = true;
        reportRequested = false;
        lastReport = "尚未采样；/rvplight occlusion 采样下一帧表面光路";
    }
}
