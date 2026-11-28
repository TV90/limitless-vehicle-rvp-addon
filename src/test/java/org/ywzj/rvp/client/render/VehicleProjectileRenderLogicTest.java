package org.ywzj.rvp.client.render;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VehicleProjectileRenderLogicTest {
    @Test
    void tracerPointsBackAlongFlightAcrossTheYawSeamAndAtSteepPitch() {
        // 接近偏航换符号的两个方向，以及近处向下射击的速度。
        List<Vec3> motions = List.of(new Vec3(5, -3, 5), new Vec3(-5, -3, 5),
                new Vec3(0.0004, -2, -8), new Vec3(-0.0004, -2, -8),
                new Vec3(0, -8, 0), new Vec3(0, 0, 8), new Vec3(0, 0, -8));
        for (Vec3 velocity : motions) {
            Vec3 back = velocity.normalize().scale(-1);
            Vector3f transformed = VehicleProjectileRenderLogic.tracerRotation(velocity, velocity.length())
                    .transform(new Vector3f(0, 0, 1));
            assertEquals(back.x, transformed.x, 0.00001, "X for " + velocity);
            assertEquals(back.y, transformed.y, 0.00001, "Y for " + velocity);
            assertEquals(back.z, transformed.z, 0.00001, "Z for " + velocity);
        }
    }
}
