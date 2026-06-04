package org.ywzj.rvp.client.laser.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Aligns stretched bedrock models along a world-space beam segment via quaternion (no yaw flip).
 */
public final class RVP_LaserBeamPose {

    /** Bedrock bullet geometry stretches along local +Z when scaled. */
    private static final Vector3f MODEL_FORWARD = new Vector3f(0.0F, 0.0F, 1.0F);

    private RVP_LaserBeamPose() {}

    public static void applyBeamTransform(PoseStack poseStack, Vec3 start, Vec3 end, float width) {
        Vec3 delta = end.subtract(start);
        double length = delta.length();
        if (length < 1.0E-4) {
            return;
        }

        Vector3f target = new Vector3f(
                (float) (delta.x / length),
                (float) (delta.y / length),
                (float) (delta.z / length));

        poseStack.translate(start.x, start.y, start.z);
        Quaternionf align = new Quaternionf().rotationTo(MODEL_FORWARD, target);
        poseStack.mulPose(align);
        poseStack.translate(0.0, 0.0, length * 0.5);
        poseStack.scale(width, width, (float) length);
    }
}
