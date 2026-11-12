package org.ywzj.rvp.mount;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.ywzj.vehicle.vehicle.pojo.Bolt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 验证"同一武器站按武器拆出弹队列，未配置武器回退整站并集"的选择语义。 */
class RVP_ShootBoltQueueResolverTest {

    private static final ResourceLocation WEAPON = new ResourceLocation("rvp", "test_weapon");

    private static Bolt bolt(double x) {
        return new Bolt(new Vec3(x, 0.0, 0.0), 1.0F, 0.0F, 0.0F);
    }

    @Test
    void weaponQueueOverridesUnion() {
        List<Bolt> union = List.of(bolt(1), bolt(2), bolt(3), bolt(4));
        List<Bolt> own = List.of(bolt(2), bolt(1));
        RVP_ShootBoltQueueResolver.StationBoltQueues queues = new RVP_ShootBoltQueueResolver.StationBoltQueues(
                union, Map.of(WEAPON, own));
        assertSame(own, queues.boltsFor(WEAPON));
    }

    @Test
    void unconfiguredWeaponFallsBackToUnion() {
        List<Bolt> union = List.of(bolt(1), bolt(2));
        RVP_ShootBoltQueueResolver.StationBoltQueues queues = new RVP_ShootBoltQueueResolver.StationBoltQueues(
                union, Map.of(WEAPON, List.of(bolt(2))));
        assertSame(union, queues.boltsFor(new ResourceLocation("rvp", "other_weapon")));
        assertSame(union, queues.boltsFor(null));
    }

    @Test
    void emptyOwnQueueFallsBackToUnion() {
        List<Bolt> union = List.of(bolt(1));
        RVP_ShootBoltQueueResolver.StationBoltQueues queues = new RVP_ShootBoltQueueResolver.StationBoltQueues(
                union, Map.of(WEAPON, List.of()));
        assertEquals(union, queues.boltsFor(WEAPON));
    }
}
