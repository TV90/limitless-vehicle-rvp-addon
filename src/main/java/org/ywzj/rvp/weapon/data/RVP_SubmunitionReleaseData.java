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

    /** 触发器列表；默认 {@code ["in_flight"]}，决定本方案在飞行、撞击或引信阶段执行。 */
    @SerializedName("triggers")
    private List<String> triggers = List.of("in_flight");

    /** 首次释放前延迟，单位 tick；默认 0，仅对飞行中调度生效。 */
    @SerializedName("delay_tick")
    private int delayTick = 0;

    /** 两次释放事件的间隔，单位 tick；默认 0，表示同一 tick 执行全部剩余事件。 */
    @SerializedName("interval_tick")
    private int intervalTick = 0;

    /**
     * 完整释放事件数；每个事件按各载荷的 {@link RVP_SubmunitionPayloadData#getCount()} 生成子体。
     * 默认 0，此时使用所有载荷 {@code count} 之和。
     */
    @SerializedName("release_events")
    private int releaseEvents = 0;

    /** 每个间隔 tick 执行的释放事件数；默认 1，最小按 1 处理。 */
    @SerializedName("per_tick")
    private int perTick = 1;

    /** 本方案生成的载荷列表；默认空列表，空时不生成子体。 */
    @SerializedName("payloads")
    private List<RVP_SubmunitionPayloadData> payloads = new ArrayList<>();

    /**
     * 本方案释放后的母弹动作；默认 {@code continue}。
     * 可选 {@code discard_after_release}、{@code discard_on_first_spawn}、
     * {@code explosion_after_release}；最后一项会在释放子体后执行母弹完整引爆链。
     */
    @SerializedName("parent_action")
    private String parentAction = "continue";

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
