package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.weapon.submunition.RVP_SubmunitionRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 子母弹 / 空中布撒配置。JSON 键 {@code submunition_data}。
 *
 * <p>新 schema 使用 {@link #releases} 描述「何时、放什么」；旧字段 {@code count} /
 * {@code delay_tick} / {@code interval_tick} / {@code spread} 仍可用，会合成一条
 * {@link RVP_EnumSubmunitionTrigger#IN_FLIGHT} 释放并克隆母弹武器（MCH {@code bomblet} 兼容）。</p>
 *
 * <p>运行时由 {@link RVP_SubmunitionRunner} 驱动，见 {@link RVP_BaseBullet}。</p>
 */
public class RVP_SubmunitionData {

    /**
     * 释放方案列表。每条可绑定不同触发器、节奏与多种 {@link RVP_SubmunitionPayloadData}。
     */
    @SerializedName("releases")
    private List<RVP_SubmunitionReleaseData> releases = new ArrayList<>();

    /** 兼容 MCH {@code bomblet}：子弹药总数（仅当 {@link #releases} 为空时生效）。 */
    @SerializedName("count")
    private int count = 0;

    /** 出生后延迟 tick（合成到 legacy release）。 */
    @SerializedName("delay_tick")
    private int delayTick = 0;

    /** 释放间隔 tick；0 = 同一 tick 齐射剩余全部。 */
    @SerializedName("interval_tick")
    private int intervalTick = 0;

    /** 速度散布（合成到 legacy payload 的 {@code box_spread}）。 */
    @SerializedName("spread")
    private float spread = 0.1f;

    public List<RVP_SubmunitionReleaseData> getReleases() {
        if (releases != null && !releases.isEmpty()) {
            return releases;
        }
        if (count <= 0) {
            return Collections.emptyList();
        }
        return List.of(RVP_SubmunitionReleaseData.legacyInFlight(count, delayTick, intervalTick, spread));
    }

    public boolean isEnabled() {
        return !getReleases().isEmpty();
    }

    /** True when only legacy {@code count}/{@code delay_tick} fields are used (no {@code releases} array). */
    public boolean usesLegacySchema() {
        return (releases == null || releases.isEmpty()) && count > 0;
    }

    /** 旧 API：总子弹药数（所有 in-flight release 的 release_events 之和）。 */
    public int getCount() {
        int total = 0;
        for (RVP_SubmunitionReleaseData release : getReleases()) {
            total += release.resolveReleaseEvents(count);
        }
        return total;
    }

    public int getDelayTick() {
        if (releases != null && !releases.isEmpty()) {
            return releases.get(0).getDelayTick();
        }
        return Math.max(delayTick, 0);
    }

    public int getIntervalTick() {
        if (releases != null && !releases.isEmpty()) {
            return releases.get(0).getIntervalTick();
        }
        return Math.max(intervalTick, 0);
    }

    public float getSpread() {
        return Math.max(spread, 0f);
    }
}
