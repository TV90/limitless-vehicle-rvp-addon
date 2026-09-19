package org.ywzj.rvp.entity.gunner.behavior.runtime;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.entity.gunner.behavior.action.RVP_GunnerActionResult;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorIntent;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 阶段 C 确定性仲裁与记录型 fake 动作边界测试。 */
class RVP_GunnerIntentArbiterTest {

    @Test
    void competingMovementAndFireIntentsExecuteOnlyOneWinnerPerChannel() {
        RVP_GunnerBehaviorIntent lowMove = intent("ground_patrol", 20, 100,
                RVP_GunnerBehaviorIntent.Channel.MOVEMENT,
                RVP_GunnerBehaviorIntent.Kind.MOVEMENT, "");
        RVP_GunnerBehaviorIntent highMove = intent("sead_revenge", 30, 950,
                RVP_GunnerBehaviorIntent.Channel.MOVEMENT,
                RVP_GunnerBehaviorIntent.Kind.MOVEMENT, "");
        RVP_GunnerBehaviorIntent regularFire = intent("weapon_engagement", 40, 500,
                RVP_GunnerBehaviorIntent.Channel.FIRE,
                RVP_GunnerBehaviorIntent.Kind.FIRE_ENGAGEMENT, "normal:1");
        RVP_GunnerBehaviorIntent seadFire = intent("sead_revenge", 50, 950,
                RVP_GunnerBehaviorIntent.Channel.FIRE,
                RVP_GunnerBehaviorIntent.Kind.FIRE_ANTI_RADIATION, "sead:1");

        RVP_GunnerIntentArbiter.Resolution resolution = new RVP_GunnerIntentArbiter().resolve(
                List.of(lowMove, regularFire, highMove, seadFire));
        RecordingExecutor fakeGateway = new RecordingExecutor();
        resolution.winners().values().forEach(winner -> fakeGateway.execute(null, winner));

        assertEquals(2, fakeGateway.executed.size());
        assertTrue(fakeGateway.executed.contains("sead_revenge/MOVEMENT"));
        assertTrue(fakeGateway.executed.contains("sead_revenge/FIRE_ANTI_RADIATION"));
        assertEquals("sead:1", resolution.winners().get("FIRE/weapon").transactionId());
        assertEquals(2, resolution.rejections().size());
    }

    @Test
    void equalPriorityUsesPlanOrderThenBehaviorIdWithoutMapIterationDependency() {
        RVP_GunnerBehaviorIntent later = intent("aaa", 20, 500,
                RVP_GunnerBehaviorIntent.Channel.FIRE,
                RVP_GunnerBehaviorIntent.Kind.FIRE_ENGAGEMENT, "later");
        RVP_GunnerBehaviorIntent earlierZ = intent("zzz", 10, 500,
                RVP_GunnerBehaviorIntent.Channel.FIRE,
                RVP_GunnerBehaviorIntent.Kind.FIRE_ENGAGEMENT, "earlier-z");
        RVP_GunnerBehaviorIntent earlierA = intent("aaa", 10, 500,
                RVP_GunnerBehaviorIntent.Channel.FIRE,
                RVP_GunnerBehaviorIntent.Kind.FIRE_ENGAGEMENT, "earlier-a");

        RVP_GunnerIntentArbiter.Resolution resolution = new RVP_GunnerIntentArbiter().resolve(
                List.of(later, earlierZ, earlierA));

        assertEquals("earlier-a", resolution.winners().get("FIRE/weapon").transactionId());
    }

    /** 创建不依赖 Minecraft 世界对象的仲裁候选。 */
    private static RVP_GunnerBehaviorIntent intent(String behaviorId, int order, int priority,
                                                   RVP_GunnerBehaviorIntent.Channel channel,
                                                   RVP_GunnerBehaviorIntent.Kind kind,
                                                   String transactionId) {
        return RVP_GunnerBehaviorIntent.of(behaviorId, channel == RVP_GunnerBehaviorIntent.Channel.FIRE
                        ? "weapon" : "vehicle", order, priority, channel, kind,
                null, null, null, false, transactionId, null);
    }

    /** 记录型 fake gateway：只记录已仲裁调用，不触碰 Minecraft/Forge 运行时。 */
    private static final class RecordingExecutor implements RVP_IGunnerIntentExecutor {
        /** 按执行顺序保存“行为/动作”记录。 */
        private final List<String> executed = new ArrayList<>();

        @Override
        public RVP_GunnerActionResult execute(
                org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorContext context,
                RVP_GunnerBehaviorIntent intent) {
            executed.add(intent.behaviorId() + "/" + intent.kind());
            return RVP_GunnerActionResult.EXECUTED;
        }
    }
}
