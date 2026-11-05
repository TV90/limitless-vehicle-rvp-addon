package org.ywzj.rvp.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.vehicle.RVP_OpticalSightMath;
import org.ywzj.rvp.vehicle.RVP_OpticalSightMotion;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 实际报文往返、状态恢复与字节预算，避免只在同进程预测中验证。 */
class RVP_OpticalSightPacketTest {
    /** 测试维度。 */
    private static final ResourceLocation DIMENSION = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    @Test void directionAndRealPointCommandsRoundTripWithoutLosingIdentityOrSequence() {
        for (Vec3 point : new Vec3[]{null, new Vec3(1234.5, 89, -4321.25)}) {
            var command = new C2SOpticalSightCommand(DIMENSION, 42, UUID.randomUUID(), 3, UUID.randomUUID(), 123,
                    RVP_OpticalSightMotion.direction(100, 25), point);
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                C2SOpticalSightCommand.encode(command, buffer);
                assertTrue(buffer.readableBytes() < 150);
                assertEquals(command, C2SOpticalSightCommand.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }

    @Test void compressedActualTrajectoryPreservesMechanicalPoseAndStabilizedRoll() {
        for (boolean stabilized : new boolean[]{true, false}) {
            Quaterniond before = RVP_OpticalSightMath.rotation(17, 122, -36);
            Quaterniond after = RVP_OpticalSightMath.rotation(17.5, 123, -35);
            var motion = new RVP_OpticalSightMotion();
            motion.reset(89.5, 4, new Quaterniond(before).rotateY(Math.toRadians(-4)).rotateX(Math.toRadians(89.5)));
            Vec3 target = RVP_OpticalSightMotion.transform(after, RVP_OpticalSightMotion.direction(91, 12));
            motion.advance(new RVP_OpticalSightMotion.Input(before, after, target,
                    new RVP_OpticalSightMotion.Limits(-40, 179, -120, 120, 7, 7, 0.1), stabilized, true));
            var message = new S2COpticalSightState(DIMENSION, 7, UUID.randomUUID(), 2, 100, UUID.randomUUID(), 80,
                    0.1, motion.snapshot(), before, after, stabilized, target, null);
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                S2COpticalSightState.encode(message, buffer);
                assertTrue(buffer.readableBytes() < 500, "单站快照须维持固定的小报文");
                var received = S2COpticalSightState.decode(buffer);
                assertEquals(message.dimension(), received.dimension()); assertEquals(message.session(), received.session());
                assertEquals(message.sequence(), received.sequence()); assertEquals(message.tick(), received.tick());
                assertArrayEquals(message.state().pitches(), received.state().pitches(), 1.0E-5);
                assertArrayEquals(message.state().yaws(), received.state().yaws(), 1.0E-5);
                var remote = new RVP_OpticalSightMotion(); remote.restore(received.state());
                for (int frame = 0; frame <= 240; frame++) {
                    double t = frame / 240.0;
                    double dot = Math.abs(motion.view(t).dot(remote.view(t)));
                    assertTrue(dot > 0.9999999, "stabilized=" + stabilized + ", t=" + t + ", pose dot=" + dot);
                }
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }
}
