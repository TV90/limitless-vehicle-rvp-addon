package org.ywzj.rvp.network.remotevisibility;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.config.RVP_CommonConfig.RemoteVehicleBillboardSource;
import org.ywzj.rvp.config.RVP_CommonConfig.RemoteVehicleSnapshotWarmupMode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S2CRemoteVehicleVisualSnapshotTest {
    /** 测试快照使用的维度 ID。 */
    private static final ResourceLocation DIMENSION =
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    /** 测试条目使用的载具实体类型 ID。 */
    private static final ResourceLocation ENTITY_TYPE =
            ResourceLocation.fromNamespaceAndPath("ywzj_vehicle", "fixed_wing_vehicle");
    /** 测试条目使用的车型数据 ID。 */
    private static final ResourceLocation VEHICLE_ID =
            ResourceLocation.fromNamespaceAndPath("rvp", "test_aircraft");
    /** 测试条目使用的显示变体 ID。 */
    private static final ResourceLocation DISPLAY_ID =
            ResourceLocation.fromNamespaceAndPath("rvp", "test_aircraft_display");

    @Test
    void codecRoundTripPreservesEveryField() {
        S2CRemoteVehicleVisualSnapshot message = new S2CRemoteVehicleVisualSnapshot(
                DIMENSION,
                12_345L,
                17L,
                true,
                true,
                RemoteVehicleBillboardSource.SLOT_TEXTURE,
                RemoteVehicleSnapshotWarmupMode.MODEL,
                List.of(entry(42)));

        assertEquals(message, roundTrip(message));
    }

    @Test
    void emptySnapshotRoundTrips() {
        S2CRemoteVehicleVisualSnapshot message =
                snapshot(0L, 0L, List.of());

        assertEquals(message, roundTrip(message));
    }

    @Test
    void maximumEntryCountIsAcceptedAndDefensivelyCopied() {
        List<S2CRemoteVehicleVisualSnapshot.Entry> mutable = new ArrayList<>();
        for (int index = 0; index < S2CRemoteVehicleVisualSnapshot.MAX_ENTRIES; index++) {
            mutable.add(entry(index));
        }
        S2CRemoteVehicleVisualSnapshot message =
                snapshot(1L, 1L, mutable);
        mutable.clear();

        assertEquals(S2CRemoteVehicleVisualSnapshot.MAX_ENTRIES, message.entries().size());
        assertEquals(message, roundTrip(message));
    }

    @Test
    void entryCountAboveLimitIsRejectedByConstructorAndDecoder() {
        List<S2CRemoteVehicleVisualSnapshot.Entry> entries = new ArrayList<>();
        for (int index = 0; index <= S2CRemoteVehicleVisualSnapshot.MAX_ENTRIES; index++) {
            entries.add(entry(index));
        }
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(1L, 1L, entries));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeResourceLocation(DIMENSION);
            buffer.writeLong(1L);
            buffer.writeLong(1L);
            writeDefaultPolicy(buffer);
            buffer.writeVarInt(S2CRemoteVehicleVisualSnapshot.MAX_ENTRIES + 1);
            assertThrows(DecoderException.class, () -> S2CRemoteVehicleVisualSnapshot.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void invalidIdentifiersAndNegativeMetadataAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(1L, -1L, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new S2CRemoteVehicleVisualSnapshot.Entry(-1, ENTITY_TYPE, VEHICLE_ID, DISPLAY_ID,
                        Vec3.ZERO, Vec3.ZERO, 0.0F, 0.0F, 0.0F, 0.0D,
                        false, false, 0.0F, 0.0F));
        assertThrows(NullPointerException.class,
                () -> new S2CRemoteVehicleVisualSnapshot.Entry(1, null, VEHICLE_ID, DISPLAY_ID,
                        Vec3.ZERO, Vec3.ZERO, 0.0F, 0.0F, 0.0F, 0.0D,
                        false, false, 0.0F, 0.0F));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeResourceLocation(DIMENSION);
            buffer.writeLong(1L);
            buffer.writeLong(1L);
            writeDefaultPolicy(buffer);
            buffer.writeVarInt(1);
            buffer.writeVarInt(1);
            buffer.writeUtf("invalid entity type");
            assertThrows(DecoderException.class, () -> S2CRemoteVehicleVisualSnapshot.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void invalidBillboardEnumOrdinalIsRejected() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeResourceLocation(DIMENSION);
            buffer.writeLong(1L);
            buffer.writeLong(1L);
            buffer.writeBoolean(true);
            buffer.writeBoolean(false);
            buffer.writeVarInt(999);
            assertThrows(DecoderException.class, () -> S2CRemoteVehicleVisualSnapshot.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void nonFiniteAndNegativeAglValuesAreRejected() {
        assertInvalidNumbers(new Vec3(Double.NaN, 2.0D, 3.0D), Vec3.ZERO,
                0.0F, 0.0F, 0.0F, 0.0D, 0.0F, 0.0F);
        assertInvalidNumbers(Vec3.ZERO, new Vec3(0.0D, Double.POSITIVE_INFINITY, 0.0D),
                0.0F, 0.0F, 0.0F, 0.0D, 0.0F, 0.0F);
        assertInvalidNumbers(Vec3.ZERO, Vec3.ZERO,
                Float.NaN, 0.0F, 0.0F, 0.0D, 0.0F, 0.0F);
        assertInvalidNumbers(Vec3.ZERO, Vec3.ZERO,
                0.0F, 0.0F, 0.0F, -0.01D, 0.0F, 0.0F);
        assertInvalidNumbers(Vec3.ZERO, Vec3.ZERO,
                0.0F, 0.0F, 0.0F, 0.0D, Float.POSITIVE_INFINITY, 0.0F);
        assertInvalidNumbers(Vec3.ZERO, Vec3.ZERO,
                0.0F, 0.0F, 0.0F, 0.0D, 0.0F, Float.NaN);
    }

    private static void assertInvalidNumbers(Vec3 position, Vec3 velocity,
                                             float xRot, float yRot, float zRot,
                                             double heightAboveGround,
                                             float power, float engineSpeed) {
        assertThrows(IllegalArgumentException.class,
                () -> new S2CRemoteVehicleVisualSnapshot.Entry(
                        1, ENTITY_TYPE, VEHICLE_ID, DISPLAY_ID,
                        position, velocity, xRot, yRot, zRot, heightAboveGround,
                        false, true, power, engineSpeed));
    }

    private static S2CRemoteVehicleVisualSnapshot.Entry entry(int entityId) {
        return new S2CRemoteVehicleVisualSnapshot.Entry(
                entityId,
                ENTITY_TYPE,
                VEHICLE_ID,
                DISPLAY_ID,
                new Vec3(12.5D, 96.0D, -23.75D),
                new Vec3(0.25D, -0.5D, 1.5D),
                12.5F,
                -179.0F,
                33.25F,
                84.5D,
                true,
                true,
                75.0F,
                62.5F);
    }

    private static S2CRemoteVehicleVisualSnapshot roundTrip(S2CRemoteVehicleVisualSnapshot message) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            S2CRemoteVehicleVisualSnapshot.encode(message, buffer);
            return S2CRemoteVehicleVisualSnapshot.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    /** 创建使用协议默认 Billboard 策略的测试快照。 */
    private static S2CRemoteVehicleVisualSnapshot snapshot(
            long gameTime, long sequence, List<S2CRemoteVehicleVisualSnapshot.Entry> entries) {
        return new S2CRemoteVehicleVisualSnapshot(
                DIMENSION,
                gameTime,
                sequence,
                true,
                false,
                RemoteVehicleBillboardSource.DYNAMIC_SNAPSHOT,
                RemoteVehicleSnapshotWarmupMode.HIDE,
                entries);
    }

    /** 向手工构造的协议缓冲写入默认 Billboard 策略头。 */
    private static void writeDefaultPolicy(FriendlyByteBuf buffer) {
        buffer.writeBoolean(true);
        buffer.writeBoolean(false);
        buffer.writeEnum(RemoteVehicleBillboardSource.DYNAMIC_SNAPSHOT);
        buffer.writeEnum(RemoteVehicleSnapshotWarmupMode.HIDE);
    }
}
