package org.ywzj.rvp.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.io.InputStream;

/**
 * 带<b>真实 mipmap 链</b>的 GUI 纹理（2026-10-06，战术地图 icon 旋转变形修复）。
 * <p>背景：icon 源图仅 32×32，战术地图画到 16~18px 再按航向任意角度旋转——旧版
 * {@code SimpleTexture} 只有 0 层 mip（{@code prepareImage(id, 0, w, h)}），旋转缩小采样时
 * 每屏幕像素只取 1 个纹素足迹，笔划粗细不均、边缘锯齿（观感"变形"）。本类加载 PNG 后
 * 手工构建 alpha 加权盒式滤波 mip 链（避免透明边黑晕），MIN 过滤器三线性
 * {@code LINEAR_MIPMAP_LINEAR}——缩小采样在相邻两级 mip 间插值，旋转观感平滑。</p>
 * <p>用法：经 {@code TextureManager.register(iconLocation, new RVP_MapIconTexture(iconLocation))}
 * 注册到原始 ResourceLocation 下，后续 {@code GuiGraphics.blit(icon, …)} 走同一管理器解析即自动采样本纹理。
 * 资源重载（F3+T）时 TextureManager 会回调 {@link #load} 重建整条链。</p>
 */
public class RVP_MapIconTexture extends AbstractTexture {

    private final ResourceLocation source;

    public RVP_MapIconTexture(ResourceLocation source) {
        this.source = source;
    }

    @Override
    public void load(ResourceManager resourceManager) throws IOException {
        this.releaseId();
        // 2026-10-07 重构（修复打开地图即崩"Image is not allocated"）：mip 链全部在纯 Java
        // int[] 数组上计算，NativeImage 仅作"逐级上传的一次性载体"（写入→上传→立即 close），
        // 不存在跨调用复用已关闭图像的可能。像素布局沿用 NativeImage 的 ABGR 打包约定。
        int[] basePixels;
        int baseW;
        int baseH;
        try (NativeImage base = NativeImage.read(
                resourceManager.getResource(this.source).orElseThrow().open())) {
            baseW = base.getWidth();
            baseH = base.getHeight();
            basePixels = new int[baseW * baseH];
            for (int y = 0; y < baseH; y++) {
                for (int x = 0; x < baseW; x++) {
                    basePixels[y * baseW + x] = base.getPixelRGBA(x, y);
                }
            }
        }
        int maxDim = Math.max(baseW, baseH);
        // mip 层数：封顶 6 级（64px 源到 1px 足够覆盖 16~18px 显示尺寸的采样需求）
        int levels = Math.min(Mth.log2(maxDim) + 1, 6);
        // 分配含 mip 链的存储（prepareImage 内部绑定本纹理 id 并逐层 texImage2D 占位）
        TextureUtil.prepareImage(this.getId(), levels, baseW, baseH);
        int[] levelPixels = basePixels;
        int lw = baseW;
        int lh = baseH;
        uploadPixels(levelPixels, lw, lh, 0);
        for (int i = 1; i < levels; i++) {
            int nw = Math.max(1, lw / 2);
            int nh = Math.max(1, lh / 2);
            levelPixels = boxDownsample(levelPixels, lw, lh, nw, nh);
            uploadPixels(levelPixels, nw, nh, i);
            lw = nw;
            lh = nh;
        }
        // 三线性 MIN 过滤器（mip 链已真实存在，setFilter(true,true) 语义安全）+ 线性 MAG + CLAMP 寻址
        this.setFilter(true, true);
    }

    /** 将 int[]（ABGR 打包）像素写入全新 NativeImage 并按 mip 层级上传，随后立即释放。 */
    private static void uploadPixels(int[] abgr, int w, int h, int level) {
        NativeImage image = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setPixelRGBA(x, y, abgr[y * w + x]);
            }
        }
        image.upload(level, 0, 0, true);
        image.close();
    }

    /**
     * alpha 加权 2×2 盒式降采样（纯 int[] 运算）：RGB 按透明度预乘平均，避免半透明边缘在缩小后出现黑晕。
     */
    private static int[] boxDownsample(int[] src, int sw, int sh, int dw, int dh) {
        int[] dst = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int x0 = Math.min(x * 2, sw - 1);
                int y0 = Math.min(y * 2, sh - 1);
                int x1 = Math.min(x0 + 1, sw - 1);
                int y1 = Math.min(y0 + 1, sh - 1);
                long sumR = 0, sumG = 0, sumB = 0, sumA = 0;
                int[] px = {src[y0 * sw + x0], src[y0 * sw + x1],
                        src[y1 * sw + x0], src[y1 * sw + x1]};
                for (int p : px) {
                    // NativeImage 像素为 ABGR 打包：R 低 8 位
                    int a = (p >>> 24) & 0xFF;
                    int b = (p >>> 16) & 0xFF;
                    int g = (p >>> 8) & 0xFF;
                    int r = p & 0xFF;
                    sumR += (long) r * a;
                    sumG += (long) g * a;
                    sumB += (long) b * a;
                    sumA += a;
                }
                if (sumA <= 0) {
                    dst[y * dw + x] = 0;
                    continue;
                }
                int r = (int) Math.min(255, sumR / sumA);
                int g = (int) Math.min(255, sumG / sumA);
                int b = (int) Math.min(255, sumB / sumA);
                int a = (int) Math.min(255, sumA / px.length);
                dst[y * dw + x] = (a << 24) | (b << 16) | (g << 8) | r;
            }
        }
        return dst;
    }
}
