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

    @SerializedName("triggers")
    private List<String> triggers = List.of("in_flight");

    @SerializedName("delay_tick")
    private int delayTick = 0;

    /** Tick gap between release events; 0 = fire all remaining in one tick. */
    @SerializedName("interval_tick")
    private int intervalTick = 0;

    /**
     * How many complete release events to run (each event spawns every payload {@link RVP_SubmunitionPayloadData#getCount()}).
     * When 0, defaults to sum of payload {@code count} values.
     */
    @SerializedName("release_events")
    private int releaseEvents = 0;

    /** MCH {@code spawnBulletPerNum}: spawns per interval tick when &gt; 1. */
    @SerializedName("per_tick")
    private int perTick = 1;

    @SerializedName("payloads")
    private List<RVP_SubmunitionPayloadData> payloads = new ArrayList<>();

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
