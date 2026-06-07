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
 * <p>使用 {@link #releases} 描述「何时、放什么」。运行时由 {@link RVP_SubmunitionRunner} 驱动，
 * 见 {@link RVP_BaseBullet}。</p>
 */
public class RVP_SubmunitionData {

    /** 释放方案列表。每条可绑定不同触发器、节奏与多种 {@link RVP_SubmunitionPayloadData}。 */
    @SerializedName("releases")
    private List<RVP_SubmunitionReleaseData> releases = new ArrayList<>();

    public List<RVP_SubmunitionReleaseData> getReleases() {
        if (releases == null || releases.isEmpty()) {
            return Collections.emptyList();
        }
        return releases;
    }

    public boolean isEnabled() {
        return !getReleases().isEmpty();
    }
}
