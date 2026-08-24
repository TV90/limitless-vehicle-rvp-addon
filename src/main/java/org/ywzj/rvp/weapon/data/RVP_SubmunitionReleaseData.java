package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One scheduled submunition release (triggers + timing + payloads).
 */
public class RVP_SubmunitionReleaseData {

    /**
     * 触发器名称列表，默认 {@code ["in_flight"]}；决定本释放方案在飞行、撞击或引信阶段何时执行，
     * 空列表同样回退到飞行中触发。
     */
    @SerializedName("triggers")
    private List<String> triggers = List.of("in_flight");

    /**
     * 飞行中首次释放前的延迟，单位 Tick，默认 {@code 0}；仅 {@code in_flight} 触发器生效，负值按 0。
     */
    @SerializedName("delay_tick")
    private int delayTick = 0;

    /**
     * 飞行中相邻释放批次的间隔，单位 Tick，默认 {@code 0}；仅 {@code in_flight} 触发器生效。
     * 0 表示同一 Tick 执行全部剩余事件，负值按 0。
     */
    @SerializedName("interval_tick")
    private int intervalTick = 0;

    /**
     * 完整释放事件数，默认 {@code 0}；每个事件为每条 payload 生成其 {@link RVP_SubmunitionPayloadData#getCount()} 数量。
     * 大于 0 时使用配置值，0 时回退为所有 payload 的 count 之和，负值按 0。
     */
    @SerializedName("release_events")
    private int releaseEvents = 0;

    /**
     * 每个间隔 Tick 执行的释放事件数，默认 {@code 1}；配置 {@code interval_tick > 0} 时生效，最小为 1。
     */
    @SerializedName("per_tick")
    private int perTick = 1;

    /**
     * 本释放方案每个事件生成的载荷配置列表，默认空列表；为空时本方案不生成任何子体。
     */
    @SerializedName("payloads")
    private List<RVP_SubmunitionPayloadData> payloads = new ArrayList<>();

    /**
     * 释放后的母弹动作，默认 {@code continue}；支持继续、全部释放后丢弃和首次成功生成后丢弃。
     */
    @SerializedName("parent_action")
    private String parentAction = "continue";

    /**
     * 是否把本释放方案生成的全部子体初始位置散布为三维椭球状云团，默认 {@code false}；
     * 释放云自身仅影响服务端权威生成位置，不直接增加速度或毁伤。payload 使用
     * {@code spread.mode=cloud_radial_horizontal} 时，会读取最终出生位置来确定水平外散方向。
     */
    @SerializedName("release_cloud_enabled")
    private boolean releaseCloudEnabled = false;

    /**
     * 释放云的水平与竖直轴半径，默认均为 {@code 4.0} 格；仅
     * {@code release_cloud_enabled=true} 时生效。字段必须使用当前 schema 的
     * {@code {"horizontal": 数值, "vertical": 数值}} 对象格式。
     */
    @SerializedName("release_cloud_radius")
    private RVP_SubmunitionReleaseCloudRadiusData releaseCloudRadius =
            new RVP_SubmunitionReleaseCloudRadiusData();

    public Set<RVP_EnumSubmunitionTrigger> getTriggers() {
        if (triggers == null || triggers.isEmpty()) {
            return EnumSet.of(RVP_EnumSubmunitionTrigger.IN_FLIGHT);
        }
        EnumSet<RVP_EnumSubmunitionTrigger> set = EnumSet.noneOf(RVP_EnumSubmunitionTrigger.class);
        for (String raw : triggers) {
            set.add(RVP_EnumSubmunitionTrigger.fromString(raw));
        }
        return set;
    }

    public int getDelayTick() {
        return Math.max(delayTick, 0);
    }

    public int getIntervalTick() {
        return Math.max(intervalTick, 0);
    }

    public int getReleaseEvents() {
        return Math.max(releaseEvents, 0);
    }

    public int getPerTick() {
        return Math.max(perTick, 1);
    }

    public List<RVP_SubmunitionPayloadData> getPayloads() {
        if (payloads == null || payloads.isEmpty()) {
            return Collections.emptyList();
        }
        return payloads;
    }

    public RVP_EnumSubmunitionParentAction getParentAction() {
        return RVP_EnumSubmunitionParentAction.fromString(parentAction);
    }

    public boolean isReleaseCloudEnabled() {
        return releaseCloudEnabled;
    }

    public float getReleaseCloudHorizontalRadius() {
        if (releaseCloudRadius == null) {
            return 4.0f;
        }
        // 调用本项目释放云半径数据访问器：取得已完成非有限值与负值处理的 X/Z 半径。
        return releaseCloudRadius.getHorizontal();
    }

    public float getReleaseCloudVerticalRadius() {
        if (releaseCloudRadius == null) {
            return 4.0f;
        }
        // 调用本项目释放云半径数据访问器：取得已完成非有限值与负值处理的 Y 半径。
        return releaseCloudRadius.getVertical();
    }

    public boolean hasPayloads() {
        return !getPayloads().isEmpty();
    }

    /**
     * Total release events for this wave when {@link #releaseEvents} is unset.
     */
    public int resolveReleaseEvents() {
        if (releaseEvents > 0) {
            return releaseEvents;
        }
        int sum = 0;
        for (RVP_SubmunitionPayloadData payload : getPayloads()) {
            sum += payload.getCount();
        }
        return Math.max(sum, 0);
    }
}
