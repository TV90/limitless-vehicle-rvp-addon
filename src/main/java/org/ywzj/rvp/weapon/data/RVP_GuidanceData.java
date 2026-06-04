package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;

import java.util.Collections;
import java.util.List;

/**
 * 分段/复合制导配置。一个 stage 表示某个飞行阶段，stage 内可配置多个按优先级尝试的 source。
 */
public class RVP_GuidanceData {

    /** 转向/刚性/预测等通用制导参数。 */
    @SerializedName("steering_data")
    private RVP_GuidanceSteeringData steeringData = new RVP_GuidanceSteeringData();

    /** 制导阶段列表。JSON 可以直接写数组，也会被注册器规范化到 stages。 */
    @SerializedName("stages")
    private List<Stage> stages = Collections.emptyList();

    public RVP_GuidanceSteeringData getSteeringData() {
        return steeringData == null ? new RVP_GuidanceSteeringData() : steeringData;
    }

    public List<Stage> getStages() {
        return stages == null ? Collections.emptyList() : stages;
    }

    public static class Stage {

        /** 阶段名称，仅用于配置可读性和调试。 */
        @SerializedName("name")
        private String name = "stage";

        /** 阶段开始 tick。 */
        @SerializedName("start_tick")
        private int startTick = 0;

        /** 阶段结束 tick；小于 0 表示不限制结束时间。 */
        @SerializedName("end_tick")
        private int endTick = -1;

        /** 阶段生效的最小目标距离，0 表示不限制。 */
        @SerializedName("min_distance")
        private float minDistance = 0f;

        /** 阶段生效的最大目标距离，0 表示不限制。 */
        @SerializedName("max_distance")
        private float maxDistance = 0f;

        /** 本阶段内的制导源列表，按 priority 从高到低尝试。 */
        @SerializedName("sources")
        private List<Source> sources = Collections.emptyList();

        public String getName() {
            return name == null ? "stage" : name;
        }

        public int getStartTick() {
            return Math.max(startTick, 0);
        }

        public int getEndTick() {
            return endTick;
        }

        public float getMinDistance() {
            return Math.max(minDistance, 0f);
        }

        public float getMaxDistance() {
            return Math.max(maxDistance, 0f);
        }

        public List<Source> getSources() {
            return sources == null ? Collections.emptyList() : sources;
        }
    }

    public static class Source {

        /** 制导源类型：NONE、IOG、MCLOS、SACLOS、GPS、IR、SARH、ARM、TV、ARH；TV/ARH 仅对 rvp:missile 生效。 */
        @SerializedName("type")
        private RVP_EnumGuidanceType type = RVP_EnumGuidanceType.NONE;

        /** 同阶段内优先级，数值越大越先尝试。 */
        @SerializedName("priority")
        private int priority = 0;

        /** 被干扰/遮挡时是否继续尝试低优先级 source。 */
        @SerializedName("fallback_on_jammed")
        private boolean fallbackOnJammed = true;

        /** 是否直接接管弹体速度。TV/MCLOS 这类直控制导常用 true。 */
        @SerializedName("take_over_motion")
        private boolean takeOverMotion = false;

        public RVP_EnumGuidanceType getType() {
            return type == null ? RVP_EnumGuidanceType.NONE : type;
        }

        public int getPriority() {
            return priority;
        }

        public boolean isFallbackOnJammed() {
            return fallbackOnJammed;
        }

        public boolean isTakeOverMotion() {
            return takeOverMotion;
        }
    }
}
