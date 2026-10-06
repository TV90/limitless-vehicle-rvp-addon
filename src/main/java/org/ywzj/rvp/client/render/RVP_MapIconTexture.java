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
        NativeImage base;
        try (InputStream in = resourceManager.getResource(this.source).orElseThrow().open()) {
            base = NativeImage.read(in);
        }
        try (base) {
            int maxDim = Math.max(base.getWidth(), base.getHeight());
            // mip 层数：封顶 6 级（64px 源到 1px 足够覆盖 16~18px 显示尺寸的采样需求）
            int levels = Math.min(Mth.log2(maxDim) + 1, 6);
            // 分配含 mip 链的存储（prepareImage 内部绑定本纹理 id 并逐层 texImage2D 占位）
            TextureUtil.prepareImage(this.getId(), levels, base.getWidth(), base.getHeight());
            uploadLevel(base, 0);
            NativeImage level = base;
            for (int i = 1; i < levels; i++) {
                NativeImage next = boxDownsample(level);
                uploadLevel(next, i);
                if (level != base) {
                    level.close();
                }
                level = next;
            }
            if (level != base) {
                level.close();
            }
        }
        // 三线性 MIN 过滤器（mip 链已真实存在，setFilter(true,true) 语义安全）+ 线性 MAG + CLAMP 寻址
        this.setFilter(true, true);
    }

    private void uploadLevel(NativeImage image, int level) {
        // 4 参 upload = (mipLevel, xOffset, yOffset, ignoreMipmapErrors)；上传前纹理已由 prepareImage 绑定
        image.upload(level, 0, 0, true);
    }

    /**
     * alpha 加权 2×2 盒式降采样：RGB 按透明度预乘平均，避免半透明边缘在缩小后出现黑晕。
     */
    private static NativeImage boxDownsample(NativeImage src) {
        int w = Math.max(1, src.getWidth() / 2);
        int h = Math.max(1, src.getHeight() / 2);
        NativeImage dst = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int x0 = Math.min(x * 2, src.getWidth() - 1);
                int y0 = Math.min(y * 2, src.getHeight() - 1);
                int x1 = Math.min(x0 + 1, src.getWidth() - 1);
                int y1 = Math.min(y0 + 1, src.getHeight() - 1);
                long sumR = 0, sumG = 0, sumB = 0, sumA = 0;
                int[] px = {src.getPixelRGBA(x0, y0), src.getPixelRGBA(x1, y0),
                        src.getPixelRGBA(x0, y1), src.getPixelRGBA(x1, y1)};
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
                    dst.setPixelRGBA(x, y, 0);
                    continue;
                }
                int r = (int) Math.min(255, sumR / sumA);
                int g = (int) Math.min(255, sumG / sumA);
                int b = (int) Math.min(255, sumB / sumA);
                int a = (int) Math.min(255, sumA / px.length);
                dst.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return dst;
    }
}
