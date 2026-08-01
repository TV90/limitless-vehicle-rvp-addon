package org.ywzj.rvp.client.resource.vehicle;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * 状态机隐藏骨骼规则：当载具进入指定状态并持续达到延时后，隐藏（不渲染）配置的骨骼。
 * <p>
 * 支持的状态：{@code landing_gear_up}（起落架收起）。
 * <p>
 * 延时说明：从进入状态开始计时 {@code delayTicks}（tick）后才隐藏，用于保证收起动作动画
 * 完整播放；状态退出时（如起落架放下）立即恢复渲染。
 */
public class RVP_StateHiddenBone {

    public final String state;
    public final List<String> bones;
    public final int delayTicks;

    public RVP_StateHiddenBone(String state, List<String> bones, int delayTicks) {
        this.state = state;
        this.bones = List.copyOf(bones);
        this.delayTicks = Math.max(0, delayTicks);
    }

    public static class Pojo {
        @SerializedName("state")
        public String state = "";

        @SerializedName("bones")
        public List<String> bones = List.of();

        @SerializedName("delay_ticks")
        public int delayTicks = 0;

        public RVP_StateHiddenBone toRule() {
            return new RVP_StateHiddenBone(state, bones, delayTicks);
        }
    }
}
