package org.ywzj.rvp.network.remotevisibility;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.virtualflight.common.RVP_VirtualFlightPhase;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.ywzj.rvp.network.remotevisibility.S2CVirtualMissileVisualSnapshot.*;

/** 直接验证生产网络编解码、实际字节预算和异常载荷，不能以另写序列化代替。 */
class S2CVirtualMissileVisualSnapshotTest {
    /** 测试使用的维度，名称不触发任何世界加载。 */
    private static final ResourceLocation DIMENSION = ResourceLocation.tryParse("minecraft:overworld");

    @Test
    void roundTripPreservesNoseMotorWindowTrailHoldAndActualRestoredPosition() {
        Entry entry = entry(1, ResourceLocation.tryParse("rvp:test"));
        Entry restored = entry(2, ResourceLocation.tryParse("rvp:test"));
        var packet = new S2CVirtualMissileVisualSnapshot(DIMENSION, UUID.randomUUID(), 8, 120,
                List.of(entry), List.of(new Departure(restored.flightUuid(), restored.entityId(),
                DepartureReason.RESTORED, restored), new Departure(new UUID(1, 3), 3, DepartureReason.TERMINATED, null),
                new Departure(new UUID(1, 4), 4, DepartureReason.OUT_OF_SCOPE, null)));
        roundTrip(packet);
        assertNotEquals(entry.motorBurnRemainingTicks(), entry.trailHoldRemainingTicks());
        assertNotEquals(entry.position(), restored.position());
    }

    @Test
    void emptyCompleteSetAndZeroRemainingBurningBoundaryRoundTrip() {
        roundTrip(new S2CVirtualMissileVisualSnapshot(DIMENSION, UUID.randomUUID(), 9, 120, List.of(), List.of()));
        Entry boundary = new Entry(new UUID(0, 8), 8, ResourceLocation.tryParse("rvp:test"), Vec3.ZERO,
                new Vec3(1, 0, 0), 179, -45, 100, RVP_VirtualFlightPhase.RESTORE_WAITING_CHUNKS,
                MotorPhase.SECOND_PULSE, 0, 40);
        roundTrip(new S2CVirtualMissileVisualSnapshot(DIMENSION, UUID.randomUUID(), 10, 120,
                List.of(boundary), List.of()));
    }

    @Test
    void exactByteBudgetIncludesLongestNamesVarIntsAndRestorationSamples() {
        ResourceLocation longest = ResourceLocation.tryParse("rvp:" + "a".repeat(MAX_RESOURCE_LENGTH - 4));
        var entries = new ArrayList<Entry>();
        var departures = new ArrayList<Departure>();
        for (int index = 0; index < MAX_ENTRIES; index++) entries.add(entry(1_000_000 + index, longest));
        for (int index = 0; index < 40; index++) {
            Entry restored = entry(2_000_000 + index, longest);
            departures.add(new Departure(restored.flightUuid(), restored.entityId(), DepartureReason.RESTORED, restored));
        }
        var packet = new S2CVirtualMissileVisualSnapshot(DIMENSION, UUID.randomUUID(), 1, 1, entries, departures);
        roundTrip(packet);
        for (int index = 40; index < MAX_DEPARTURES; index++) {
            Entry restored = entry(2_000_000 + index, longest);
            departures.add(new Departure(restored.flightUuid(), restored.entityId(), DepartureReason.RESTORED, restored));
        }
        assertThrows(IllegalArgumentException.class, () -> new S2CVirtualMissileVisualSnapshot(
                DIMENSION, UUID.randomUUID(), 1, 1, entries, departures));
    }

    @Test
    void decodeRejectsOversizedCountsBeforeAllocatingEntries() {
        for (int count : new int[]{-1, MAX_ENTRIES + 1, Integer.MAX_VALUE}) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeResourceLocation(DIMENSION); buffer.writeUUID(UUID.randomUUID());
                buffer.writeLong(1); buffer.writeLong(1); buffer.writeVarInt(count);
                assertThrows(DecoderException.class, () -> decode(buffer));
            } finally { buffer.release(); }
        }
    }

    @Test
    void decodeRejectsOversizedPayloadAndResourceName() {
        FriendlyByteBuf huge = new FriendlyByteBuf(Unpooled.buffer(MAX_PACKET_BYTES + 1));
        FriendlyByteBuf name = new FriendlyByteBuf(Unpooled.buffer());
        try {
            huge.writeZero(MAX_PACKET_BYTES + 1);
            assertThrows(DecoderException.class, () -> decode(huge));
            name.writeUtf("rvp:" + "x".repeat(MAX_RESOURCE_LENGTH));
            assertThrows(DecoderException.class, () -> decode(name));
        } finally { huge.release(); name.release(); }
    }

    @Test
    void corruptedCoordinatesEnumAndTrailingBytesNeverReachState() {
        var message = new S2CVirtualMissileVisualSnapshot(DIMENSION, UUID.randomUUID(), 1, 1,
                List.of(entry(1, ResourceLocation.tryParse("rvp:test"))), List.of());
        for (int corruption = 0; corruption < 4; corruption++) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                encode(message, buffer);
                buffer.readResourceLocation(); buffer.readUUID(); buffer.readLong(); buffer.readLong(); buffer.readVarInt();
                buffer.readUUID(); buffer.readVarInt(); buffer.readResourceLocation();
                int positionOffset = buffer.readerIndex();
                if (corruption == 0) buffer.setDouble(positionOffset, Double.NaN);
                if (corruption == 1) buffer.setDouble(positionOffset + 24, Double.POSITIVE_INFINITY);
                if (corruption == 2) {
                    buffer.skipBytes(56); buffer.readVarInt(); buffer.setByte(buffer.readerIndex(), 255);
                }
                if (corruption == 3) buffer.writeByte(1);
                buffer.readerIndex(0);
                assertThrows(DecoderException.class, () -> decode(buffer));
            } finally { buffer.release(); }
        }
    }

    @Test
    void duplicateIdentityAndMismatchedRestoredIdentityAreRejectedAtEncodeBoundary() {
        Entry first = entry(1, ResourceLocation.tryParse("rvp:test"));
        assertThrows(IllegalArgumentException.class, () -> new S2CVirtualMissileVisualSnapshot(DIMENSION,
                UUID.randomUUID(), 1, 1, List.of(first, first), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Departure(UUID.randomUUID(), first.entityId(),
                DepartureReason.RESTORED, first));
        assertThrows(IllegalArgumentException.class, () -> new Departure(first.flightUuid(), first.entityId(),
                DepartureReason.RESTORED, null));
        assertThrows(IllegalArgumentException.class, () -> new Entry(first.flightUuid(), 1, first.weaponId(), Vec3.ZERO,
                Vec3.ZERO, 0, 0, -1, first.phase(), first.motorPhase(), 0, 0));
    }

    /** 使用生产编码/解码并核对真实长度，返回值不能夹带未消费字节。 */
    private static void roundTrip(S2CVirtualMissileVisualSnapshot message) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            encode(message, buffer);
            assertEquals(encodedSize(message.dimension(), message.entries(), message.departures()), buffer.readableBytes());
            assertTrue(buffer.readableBytes() <= MAX_PACKET_BYTES);
            assertEquals(message, decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }

    /** 机头故意不沿速度方向，当前燃烧窗口与最晚燃尽也故意不同。 */
    private static Entry entry(int id, ResourceLocation weapon) {
        return new Entry(new UUID(0, id), id, weapon, new Vec3(id, 100, -200), new Vec3(3, 1, 0),
                179, -30, 1_000_000, RVP_VirtualFlightPhase.VIRTUAL_CRUISE, MotorPhase.PRIMARY, 17, 1200);
    }
}
