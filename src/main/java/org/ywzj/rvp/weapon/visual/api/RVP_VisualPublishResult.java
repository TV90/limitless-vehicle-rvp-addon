package org.ywzj.rvp.weapon.visual.api;

/**
 * 一次爆炸视觉发布结果。
 *
 * @param publishedCount 成功发布的视觉事件数量
 * @param shouldSuppressNativeExplosionEffect 是否应复用现有门面屏蔽本体普通爆炸视觉
 * @param shockwavePublished 是否已成功发布可显示的完整冲击波，用于避免自动效果重复追加
 */
public record RVP_VisualPublishResult(int publishedCount, boolean shouldSuppressNativeExplosionEffect,
                                     boolean shockwavePublished) {
    /** 没有配置或没有有效事件时的结果。 */
    public static final RVP_VisualPublishResult NONE = new RVP_VisualPublishResult(0, false, false);

    public RVP_VisualPublishResult {
        if (publishedCount < 0) {
            throw new IllegalArgumentException("publishedCount must not be negative");
        }
        if (publishedCount == 0 && (shouldSuppressNativeExplosionEffect || shockwavePublished)) {
            throw new IllegalArgumentException("native visuals cannot be suppressed without a published event");
        }
    }
}
