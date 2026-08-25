package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RVP_SubmunitionReleaseDataTest {

    /** 测试当前 JSON schema 解析行为使用的 Gson 实例。 */
    private static final Gson GSON = new Gson();

    @Test
    void releaseCloudDefaultsToDisabledWithFourBlockAxisRadii() {
        RVP_SubmunitionReleaseData release = GSON.fromJson("{}", RVP_SubmunitionReleaseData.class);

        // 调用本项目 release 数据访问器：验证未配置 JSON 的兼容默认值。
        assertFalse(release.isReleaseCloudEnabled());
        assertEquals(4.0f, release.getReleaseCloudHorizontalRadius());
        assertEquals(4.0f, release.getReleaseCloudVerticalRadius());
    }

    @Test
    void releaseCloudParsesExplicitConfiguration() {
        RVP_SubmunitionReleaseData release = GSON.fromJson("""
                {
                  "release_cloud_enabled": true,
                  "release_cloud_radius": {
                    "horizontal": 6.5,
                    "vertical": 2.25
                  }
                }
                """, RVP_SubmunitionReleaseData.class);

        // 调用本项目 release 数据访问器：验证显式开关与两个轴半径分别进入运行时模型。
        assertTrue(release.isReleaseCloudEnabled());
        assertEquals(6.5f, release.getReleaseCloudHorizontalRadius());
        assertEquals(2.25f, release.getReleaseCloudVerticalRadius());
    }

    @Test
    void releaseCloudRadiusClampsNegativeAndRecoversNonFiniteValues() {
        RVP_SubmunitionReleaseData invalid = GSON.fromJson("""
                {
                  "release_cloud_radius": {
                    "horizontal": -3.0,
                    "vertical": "NaN"
                  }
                }
                """, RVP_SubmunitionReleaseData.class);
        RVP_SubmunitionReleaseData nullRadius = GSON.fromJson("""
                {"release_cloud_radius": null}
                """, RVP_SubmunitionReleaseData.class);

        // 调用本项目 release 数据访问器：验证各轴边界值及空对象按当前 schema 独立归一化。
        assertEquals(0.0f, invalid.getReleaseCloudHorizontalRadius());
        assertEquals(4.0f, invalid.getReleaseCloudVerticalRadius());
        assertEquals(4.0f, nullRadius.getReleaseCloudHorizontalRadius());
        assertEquals(4.0f, nullRadius.getReleaseCloudVerticalRadius());
    }

    @Test
    void releaseCloudRadiusRejectsRemovedScalarShape() {
        // 调用 Gson 当前 schema 解析：确认旧标量形状不会被迁移或作为别名继续接受。
        assertThrows(JsonSyntaxException.class, () -> GSON.fromJson("""
                {"release_cloud_radius": 4.0}
                """, RVP_SubmunitionReleaseData.class));
    }
}
