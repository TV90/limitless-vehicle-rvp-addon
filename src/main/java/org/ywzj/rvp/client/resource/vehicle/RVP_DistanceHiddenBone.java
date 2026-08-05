package org.ywzj.rvp.client.resource.vehicle;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * 距离 LOD 隐藏骨骼规则：当当前玩家与载具的距离超过 {@code distance}（方块）时，
 * 隐藏（不渲染）配置的骨骼；回到距离内立即恢复渲染。
 */
public class RVP_DistanceHiddenBone {

    public final double distance;
    public final List<String> bones;

    public RVP_DistanceHiddenBone(double distance, List<String> bones) {
        this.distance = Math.max(0, distance);
        this.bones = List.copyOf(bones);
    }

    public static class Pojo {
        @SerializedName("distance")
        public double distance = 50.0;

        @SerializedName("bones")
        public List<String> bones = List.of();

        public RVP_DistanceHiddenBone toRule() {
            return new RVP_DistanceHiddenBone(distance, bones);
        }
    }
}
