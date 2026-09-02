package org.ywzj.rvp.client.compat.distanthorizons;

import org.joml.Matrix4f;

/** 从 DH API 事件复制出的本帧纯 RVP 参数，避免后续模块持有 API 的池化对象。 */
public record RVP_DhRenderParameters(
        Matrix4f projection,
        float nearPlane,
        float farPlane,
        float partialTick,
        String renderPass) {

    /** 建立防御性矩阵副本。 */
    public RVP_DhRenderParameters {
        projection = new Matrix4f(projection);
    }
}
