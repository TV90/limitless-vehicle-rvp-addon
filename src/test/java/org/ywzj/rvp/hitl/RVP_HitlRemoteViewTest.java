package org.ywzj.rvp.hitl;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.server.hitl.RVP_HitlRadioPath;
import org.ywzj.rvp.weapon.data.RVP_GuidanceData;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataAdapter;
import org.ywzj.rvp.weapon.data.RVP_GuidanceDataHITL;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

/** 远程能力的配置门控、预算边界、移动窗口和未知无线电路径回归测试。 */
class RVP_HitlRemoteViewTest {
    /** 经实际武器适配器解析，避免测试绕过 guidance_type 校验。 */
    private final Gson gson = new Gson();

    @Test void missingObjectAndMissingEnableKeepBothHitlTypesUnchanged() {
        for (String type : new String[] {"HITL_TV", "HITL_CLOS_TV"}) {
            assertFalse(parse(type, "").isRemoteViewEnabled());
            assertFalse(parse(type, ",\"hitl_remote_view\":{}").isRemoteViewEnabled());
            assertFalse(parse(type, ",\"hitl_remote_view\":{\"enabled\":false}").isRemoteViewEnabled());
        }
    }

    @Test void explicitEnableAllowsBothHitlTypesWithSmallDefaults() {
        for (String type : new String[] {"HITL_TV", "HITL_CLOS_TV"}) {
            var data = parse(type, ",\"hitl_remote_view\":{\"enabled\":true}");
            assertTrue(data.isRemoteViewEnabled());
            var remote = data.getHitlRemoteView();
            assertEquals(6, remote.getRadiusChunks());
            assertEquals(1, remote.getLoadChunksPerTick());
            assertEquals(2, remote.getSendChunksPerTick());
            assertEquals(1, remote.getPrefetchChunks());
            assertEquals(5, remote.getLinkCheckIntervalTicks());
            assertFalse(remote.isRequireLoadedRadioPath());
        }
    }

    @Test void remoteFieldCannotAttachToUnrelatedGuidance() {
        for (String type : new String[] {"TV", "GPS", "IR", "ARH"}) {
            assertThrows(JsonParseException.class, () -> parse(type,
                    ",\"hitl_remote_view\":{\"enabled\":true}"));
        }
    }

    @Test void wrongShapeDoesNotSilentlyActivate() {
        assertThrows(JsonParseException.class, () -> parse("HITL_TV", ",\"hitl_remote_view\":true"));
        assertFalse(parse("HITL_TV", ",\"hitl_remote_view\":null").isRemoteViewEnabled());
    }

    @Test void hostileConfigValuesAreBounded() {
        var low = parse("HITL_TV", """
                ,"hitl_remote_view":{"enabled":true,"radius_chunks":-10,"load_chunks_per_tick":0,
                "send_chunks_per_tick":-50,"prefetch_chunks":-5,"link_check_interval_ticks":0}
                """).getHitlRemoteView();
        assertEquals(2, low.getRadiusChunks());
        assertEquals(1, low.getLoadChunksPerTick());
        assertEquals(1, low.getSendChunksPerTick());
        assertEquals(0, low.getPrefetchChunks());
        assertEquals(1, low.getLinkCheckIntervalTicks());
        var high = parse("HITL_TV", """
                ,"hitl_remote_view":{"enabled":true,"radius_chunks":999,"load_chunks_per_tick":999,
                "send_chunks_per_tick":999,"prefetch_chunks":999,"link_check_interval_ticks":999}
                """).getHitlRemoteView();
        assertEquals(12, high.getRadiusChunks());
        assertEquals(4, high.getLoadChunksPerTick());
        assertEquals(8, high.getSendChunksPerTick());
        assertEquals(2, high.getPrefetchChunks());
        assertEquals(20, high.getLinkCheckIntervalTicks());
    }

    @Test void radiusSixMeans169ChunksAndCenterGetsFirstBudget() {
        var window = new RVP_HitlRemoteWindow(-100, -71, 6);
        var chunks = window.chunks();
        assertEquals(169, chunks.size());
        assertEquals(169, new HashSet<>(chunks).size());
        assertEquals(ChunkPos.asLong(-100, -71), chunks.get(0));
        assertTrue(chunks.stream().allMatch(window::contains));
        assertTrue(window.contains(-106, -77));
        assertFalse(window.contains(-107, -77));
    }

    @Test void windowMovesWithoutRetainingTheFlightPath() {
        var original = new RVP_HitlRemoteWindow(0, 0, 6);
        var moved = new RVP_HitlRemoteWindow(128, 0, 6);
        assertEquals(original.chunks().size(), moved.chunks().size());
        assertFalse(moved.chunks().stream().anyMatch(original::contains));
        var oneStep = new RVP_HitlRemoteWindow(1, 0, 6);
        assertEquals(13, oneStep.chunks().stream().filter(key -> !original.contains(key)).count());
    }

    @Test void prefetchIsDirectionalDeduplicatedAndBounded() {
        var window = new RVP_HitlRemoteWindow(-4, -7, 6);
        var forward = window.leases(1, 0, 1);
        assertEquals(182, forward.size());
        assertEquals(forward.size(), new HashSet<>(forward).size());
        assertTrue(forward.contains(ChunkPos.asLong(3, -7)));
        assertFalse(forward.contains(ChunkPos.asLong(-11, -7)));
        assertEquals(window.chunks(), forward.subList(0, 169));
        var diagonal = window.leases(-1, -1, Integer.MAX_VALUE);
        assertEquals(217, diagonal.size());
        assertTrue(diagonal.contains(ChunkPos.asLong(-12, -15)));
    }

    @Test void stationaryAndInvalidVelocityNeverExpandWindow() {
        var window = new RVP_HitlRemoteWindow(0, 0, 6);
        assertEquals(window.chunks(), window.leases(0, 0, 2));
        assertEquals(window.chunks(), window.leases(Double.NaN, 1, 2));
        assertEquals(window.chunks(), window.leases(1, Double.POSITIVE_INFINITY, 2));
        assertEquals(window.chunks(), window.leases(1, 1, 0));
        assertFalse(window.contains(Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    @Test void unknownRadioPathPolicyIsExplicitAndNeverHidesKnownObstacle() {
        for (boolean strict : new boolean[] {false, true}) {
            assertFalse(RVP_HitlRadioPath.blocks(RVP_HitlRadioPath.Result.CLEAR, strict));
            assertTrue(RVP_HitlRadioPath.blocks(RVP_HitlRadioPath.Result.BLOCKED, strict));
        }
        assertFalse(RVP_HitlRadioPath.blocks(RVP_HitlRadioPath.Result.UNKNOWN, false));
        assertTrue(RVP_HitlRadioPath.blocks(RVP_HitlRadioPath.Result.UNKNOWN, true));
    }

    private RVP_GuidanceDataHITL parse(String type, String fields) {
        String json = "{\"guidance\":{\"guidance_type\":\"" + type + "\"" + fields + "}}";
        return (RVP_GuidanceDataHITL) gson.fromJson(json, Holder.class).guidance;
    }

    private static class Holder {
        /** 使用与武器相同的适配器。 */
        @JsonAdapter(RVP_GuidanceDataAdapter.class)
        RVP_GuidanceData guidance;
    }
}
