package org.ywzj.rvp.replay;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.network.S2CRvpHitIndicator;
import org.ywzj.rvp.vehicle.BoneModuleType;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RVP_HitReplayTest {
    private static RVP_HitReplay.Frame frame(long tick, float yaw, boolean broken) {
        return new RVP_HitReplay.Frame(tick, new Vec3(20_000_000.125 + tick, 80, 3), new Vec3(1, 0, 0),
                0, yaw, 0, broken ? 400 : 900, false,
                List.of(new RVP_HitReplay.Part(0, 10, yaw, -1), new RVP_HitReplay.Part(1, 0, 0, broken ? 1 : 0)),
                broken ? Map.of(0, 10f) : Map.of(), broken ? Set.of(2) : Set.of(),
                broken ? Map.of("turretring", Set.of(BoneModuleType.TURRET_TRAVERSE)) : Map.of());
    }

    @Test void historyIsBoundedAndSameTickReplacesImpactState() {
        var window = new RVP_HitReplay.Window<Integer>();
        for (int i = 0; i < 1000; i++) window.put(i, i);
        assertEquals(21, window.all().size());
        assertEquals(979, window.all().get(0));
        window.put(999, -1);
        assertEquals(List.of(-1), window.after(998));
        window.put(100, 1);
        assertEquals(21, window.all().size());
    }

    @Test void angularWrapInterpolatesWithoutEarlyDestructionAndFreezesAtImpact() {
        var history = List.of(frame(10, 179, false), frame(11, -179, true));
        var middle = RVP_HitReplay.sample(history, 10.5);
        assertEquals(180, middle.yaw(), 1e-5);
        assertEquals(180, middle.parts().get(0).yaw(), 1e-5);
        assertEquals(900, middle.health());
        assertTrue(middle.inactive().isEmpty());
        assertTrue(middle.detached().isEmpty());
        assertEquals(0, middle.parts().get(1).toggle());
        assertEquals(history.get(1), RVP_HitReplay.sample(history, 11));
        assertEquals(history.get(1), RVP_HitReplay.sample(history, 1000));
    }

    @Test void packetRoundTripPreservesSubBlockPrecisionAndDetachedPartState() {
        var message = S2CRvpHitIndicator.create(31, new Vec3(20_000_010.125, 80, 3), new Vec3(0, 0, 1),
                50, "炮塔", "", "rvp:shell", new Vec3(20_000_010.125, 80, 3), "rvp:tank", 0, "turret");
        var id = new ResourceLocation("rvp", "tank");
        message.replay = new RVP_HitReplay.Clip(UUID.randomUUID(), id, id, id, 11, true,
                List.of(frame(10, 179, false), frame(11, -179, true)),
                List.of(new RVP_HitReplay.Point(10, new Vec3(20_000_010.125, 80, 3))),
                new RVP_HitReplay.Anchor(0, 2, new Vec3(.25, .5, .75), new Vec3(0, 0, -1)));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            S2CRvpHitIndicator.encode(message, buffer);
            var copy = S2CRvpHitIndicator.decode(buffer);
            assertEquals(message.replay, copy.replay);
            assertFalse(buffer.isReadable());
        } finally { buffer.release(); }
    }

    @Test void projectileStartsAtBirthAndDoesNotBridgeVirtualizationGap() {
        var points = List.of(new RVP_HitReplay.Point(10, new Vec3(1, 2, 3)),
                new RVP_HitReplay.Point(11, new Vec3(3, 2, 3)),
                new RVP_HitReplay.Point(16, new Vec3(20, 2, 3)));
        assertNull(RVP_HitReplay.projectileAt(points, 9));
        assertEquals(new Vec3(2, 2, 3), RVP_HitReplay.projectileAt(points, 10.5));
        assertNull(RVP_HitReplay.projectileAt(points, 14));
        assertEquals(new Vec3(20, 2, 3), RVP_HitReplay.projectileAt(points, 16));
    }

    @Test void consecutiveHitsSendOnlyNewFramesInsteadOfWholeSecond() {
        var history = new RVP_HitReplay.Window<RVP_HitReplay.Frame>();
        for (int i = 0; i <= 20; i++) history.put(i, frame(i, i, false));
        assertEquals(21, history.all().size());
        history.put(21, frame(21, 21, true));
        // 最后端点允许覆盖，余下 19 帧无需每发重传。
        assertEquals(List.of(frame(20, 20, false), frame(21, 21, true)), history.after(19));
    }

    @Test void snapshotsDoNotChangeWhenLiveCollectionsMutate() {
        var modules = new HashMap<String, Set<BoneModuleType>>();
        var types = EnumSet.of(BoneModuleType.ERA);
        modules.put("armor", types);
        var frozen = new RVP_HitReplay.Frame(0, Vec3.ZERO, Vec3.ZERO, 0, 0, 0, 100, false,
                List.of(), Map.of(), Set.of(), modules);
        types.clear(); modules.clear();
        assertEquals(Set.of(BoneModuleType.ERA), frozen.inactive().get("armor"));
    }
}
