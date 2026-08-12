package org.ywzj.rvp.client.visual.thermobaric;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.slf4j.Logger;
import org.ywzj.rvp.RVP_MOD;

import java.io.IOException;
import java.io.InputStream;

/** 在客户端资源重载时解析温压 billboard 贴图的 alpha 有效覆盖率。 */
public final class RVP_ThermobaricParticleCoverage implements ResourceManagerReloadListener {
    /** 客户端资源重载监听器单例。 */
    public static final RVP_ThermobaricParticleCoverage INSTANCE =
            new RVP_ThermobaricParticleCoverage();
    /** 温压火球、凝结云、尘环与后燃云共同使用的粒子贴图。 */
    static final ResourceLocation PARTICLE_TEXTURE =
            RVP_MOD.modLocation("textures/nuclear/particle_base.png");
    /** 内置 16×16 贴图的非透明像素占比，资源读取失败时使用。 */
    static final float FALLBACK_COVERAGE = 116.0F / 256.0F;
    /** 资源重载失败警告使用的日志器。 */
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 当前资源包粒子贴图按 alpha 加权后的有效面积比例。 */
    private volatile float effectiveCoverage = FALLBACK_COVERAGE;

    private RVP_ThermobaricParticleCoverage() {
    }

    /** 返回最近一次资源重载得到的有效面积比例。 */
    static float effectiveCoverage() {
        return INSTANCE.effectiveCoverage;
    }

    /** 读取当前资源包中的真实贴图，使资源包替换后的预算仍与可见面积一致。 */
    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        try (InputStream inputStream = resourceManager.getResourceOrThrow(PARTICLE_TEXTURE).open();
                NativeImage image = NativeImage.read(inputStream)) {
            long alphaSum = 0L;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    // NativeImage#getPixelRGBA 返回高八位为 alpha 的 ABGR 整数。
                    alphaSum += image.getPixelRGBA(x, y) >>> 24;
                }
            }
            effectiveCoverage = resolveEffectiveCoverage(alphaSum,
                    (long) image.getWidth() * image.getHeight());
        } catch (IOException | RuntimeException exception) {
            effectiveCoverage = FALLBACK_COVERAGE;
            LOGGER.warn("读取温压粒子贴图 {} 的有效覆盖率失败，已回退内置比例 {}",
                    PARTICLE_TEXTURE, FALLBACK_COVERAGE, exception);
        }
    }

    /** 按所有像素 alpha 总和计算有效面积比例；完全透明贴图合法返回零。 */
    static float resolveEffectiveCoverage(long alphaSum, long pixelCount) {
        if (pixelCount <= 0L || alphaSum < 0L) {
            return FALLBACK_COVERAGE;
        }
        double maximumAlphaSum = pixelCount * 255.0D;
        return (float) Math.max(0.0D, Math.min(1.0D, alphaSum / maximumAlphaSum));
    }
}
