package org.ywzj.rvp.firesupport;

import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.firesupport.config.RVP_FireSupportProfileNetworkCodec;
import org.ywzj.rvp.firesupport.config.RVP_FireSupportProfileParser;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportProfile;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportEndReason;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportMissionState;
import org.ywzj.rvp.network.firesupport.C2SRequestFireSupport;
import org.ywzj.rvp.network.firesupport.C2SRequestFireSupportCeaseFire;
import org.ywzj.rvp.network.firesupport.S2CFireSupportMissionUpdate;
import org.ywzj.rvp.network.firesupport.S2CFireSupportProfileSnapshot;
import org.ywzj.rvp.network.firesupport.S2CFireSupportRequestResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RVP_FireSupportStageCProtocolTest {
    @Test
    void requestPacketRoundTripsBoundedSelectionsAndParameters() {
        UUID nonce = UUID.randomUUID();
        C2SRequestFireSupport input = new C2SRequestFireSupport(17, InteractionHand.OFF_HAND,
                RVP_FireSupportTestProfiles.PROFILE_ID,
                "he", "effect", "line", 123.5, -456.25, 92.0,
                Map.of("length_m", 120.0, "width_m", 24.0), nonce);
        assertEquals(input, roundTrip(input, C2SRequestFireSupport::encode, C2SRequestFireSupport::decode));
    }

    @Test
    void requestAndCeaseMessagesDefensivelyCopyAndRoundTrip() {
        Map<String, Double> mutable = new LinkedHashMap<>();
        mutable.put("radius_m", 18.0);
        C2SRequestFireSupport request = new C2SRequestFireSupport(3, InteractionHand.MAIN_HAND,
                RVP_FireSupportTestProfiles.PROFILE_ID,
                "he", "rapid", "point", 20, 30, 0, mutable, UUID.randomUUID());
        mutable.put("radius_m", 32.0);
        assertEquals(18.0, request.parameters().get("radius_m"));
        assertThrows(UnsupportedOperationException.class, () -> request.parameters().put("x", 1.0));

        C2SRequestFireSupportCeaseFire cease = new C2SRequestFireSupportCeaseFire(UUID.randomUUID(), UUID.randomUUID());
        assertEquals(cease, roundTrip(cease, C2SRequestFireSupportCeaseFire::encode,
                C2SRequestFireSupportCeaseFire::decode));
    }

    @Test
    void normalizedProfileSnapshotCanBeParsedBackByCurrentSchema() {
        RVP_FireSupportProfile profile = RVP_FireSupportTestProfiles.parse();
        String json = RVP_FireSupportProfileNetworkCodec.encode(profile);
        RVP_FireSupportProfile reparsed = RVP_FireSupportProfileParser.parseAll(
                Map.of(RVP_FireSupportTestProfiles.PROFILE_ID, JsonParser.parseString(json)),
                id -> RVP_FireSupportTestProfiles.WEAPON_ID.equals(id)
                        ? RVP_FireSupportTestProfiles.projectileWeapon() : null)
                .get(RVP_FireSupportTestProfiles.PROFILE_ID);
        assertEquals(profile.callStage(), reparsed.callStage());
        assertEquals(profile.limits(), reparsed.limits());
        assertEquals(profile.fireModes().keySet(), reparsed.fireModes().keySet());
        assertEquals(profile.patterns().keySet(), reparsed.patterns().keySet());

        S2CFireSupportProfileSnapshot packet = new S2CFireSupportProfileSnapshot(9,
                Map.of(RVP_FireSupportTestProfiles.PROFILE_ID, json));
        assertEquals(packet, roundTrip(packet, S2CFireSupportProfileSnapshot::encode,
                S2CFireSupportProfileSnapshot::decode));
    }

    @Test
    void resultAndMissionUpdatesPreserveAuthoritativeTicksAndReasons() {
        UUID missionId = UUID.randomUUID();
        S2CFireSupportRequestResult result = new S2CFireSupportRequestResult(UUID.randomUUID(), false, true,
                RVP_FireSupportEndReason.NONE, missionId, 1234L, Map.of("radius_m", 18.0),
                800L, 800L, 860L);
        assertEquals(result, roundTrip(result, S2CFireSupportRequestResult::encode,
                S2CFireSupportRequestResult::decode));

        S2CFireSupportMissionUpdate update = new S2CFireSupportMissionUpdate(missionId, UUID.randomUUID(),
                RVP_FireSupportMissionState.CANCELLED, RVP_FireSupportEndReason.TERMINAL_LOST,
                0, 9, 800L, Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(update, roundTrip(update, S2CFireSupportMissionUpdate::encode,
                S2CFireSupportMissionUpdate::decode));
    }

    private static <T> T roundTrip(T value, Encoder<T> encoder, Decoder<T> decoder) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            encoder.encode(value, buffer);
            return decoder.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    @FunctionalInterface
    private interface Encoder<T> {
        /** 把测试值写入缓冲。 */ void encode(T value, FriendlyByteBuf buffer);
    }

    @FunctionalInterface
    private interface Decoder<T> {
        /** 从缓冲读取测试值。 */ T decode(FriendlyByteBuf buffer);
    }
}
