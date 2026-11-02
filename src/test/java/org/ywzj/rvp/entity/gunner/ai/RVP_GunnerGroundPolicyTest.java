package org.ywzj.rvp.entity.gunner.ai;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.entity.gunner.ai.RVP_GunnerGroundPolicy.*;

import static org.junit.jupiter.api.Assertions.*;

/** 验证抽取前的重要地面行为合同，包括同时递减的战术计时与脱困抢占。 */
class RVP_GunnerGroundPolicyTest {
    /** 使用原默认数值的参数夹具。 */
    private static final Settings SETTINGS = new Settings(12,100,140,140,55,20,1,20,300,300,120,120,40);
    /** 固定随机接口，避免测试依赖概率。 */
    private static final RandomInput RANDOM = new RandomInput() {
        @Override public int nextInt(int bound) { return 0; }
        @Override public float nextFloat() { return 0; }
        @Override public boolean nextBoolean() { return true; }
    };

    /** 目标在车前，yaw 改变用于检查角度边界。 */
    private static Input input(long tick, double distance, float yaw, boolean launcher, boolean ammo) {
        return new Input(tick,new Point(0,0),yaw,-Math.sin(Math.toRadians(yaw)),Math.cos(Math.toRadians(yaw)),
                new Point(0,distance),true,launcher,ammo);
    }

    @Test void holdsThenEvadesWithOriginalSimultaneousTimers() {
        var state=new GroundEngagementState();
        assertEquals("HOLD",RVP_GunnerGroundPolicy.engage(input(0,10,0,false,true),SETTINGS,state,RANDOM).phase());
        for (int tick=1;tick<=140;tick++) {
            state.tick();
            var command=RVP_GunnerGroundPolicy.engage(input(tick,10,0,false,true),SETTINGS,state,RANDOM);
            assertEquals(tick<100 || tick==140 ? "HOLD" : "EVADE",command.phase(),"tick="+tick);
        }
        var lost=new Input(141,new Point(0,0),0,0,1,null,false,false,true);
        assertEquals("NO_TARGET",RVP_GunnerGroundPolicy.engage(lost,SETTINGS,state,RANDOM).phase());
        assertTrue(RVP_GunnerGroundPolicy.engage(input(142,100,0,false,true),SETTINGS,state,RANDOM).forward());
    }

    @Test void steeringAndForwardKeepTheirStrictAngleBoundaries() {
        assertFalse(RVP_GunnerGroundPolicy.engage(input(1,100,-8,false,true),SETTINGS,new GroundEngagementState(),RANDOM).right());
        assertTrue(RVP_GunnerGroundPolicy.engage(input(1,100,-8.1f,false,true),SETTINGS,new GroundEngagementState(),RANDOM).right());
        assertFalse(RVP_GunnerGroundPolicy.engage(input(1,100,80,false,true),SETTINGS,new GroundEngagementState(),RANDOM).forward());
        assertTrue(RVP_GunnerGroundPolicy.engage(input(1,100,79.9f,false,true),SETTINGS,new GroundEngagementState(),RANDOM).forward());
        assertFalse(RVP_GunnerGroundPolicy.engage(input(1,SETTINGS.stopDistance()*1.2,0,false,true),SETTINGS,new GroundEngagementState(),RANDOM).forward());
    }

    @Test void recoveryLastsExactlyTwentyTicksAndLoadedLauncherDoesNotTriggerIt() {
        var state=new StuckRecoveryState();
        for (int tick=1;tick<20;tick++) {
            state.tick(); assertNull(RVP_GunnerGroundPolicy.recover(input(tick,100,0,false,true),SETTINGS,state));
        }
        for (int tick=20;tick<40;tick++) {
            state.tick();
            var command=RVP_GunnerGroundPolicy.recover(input(tick,100,0,false,true),SETTINGS,state);
            assertNotNull(command); assertTrue(command.backward()); assertTrue(command.left());
        }
        state.tick(); assertNull(RVP_GunnerGroundPolicy.recover(input(40,100,0,false,true),SETTINGS,state));
        assertNull(RVP_GunnerGroundPolicy.recover(input(20,100,0,true,true),SETTINGS,new StuckRecoveryState()));
        assertNotNull(RVP_GunnerGroundPolicy.recover(input(20,100,0,true,false),SETTINGS,new StuckRecoveryState()));
    }

    @Test void patrolRotatesWithoutForwardAndLauncherParksAfterReload() {
        var state=new GroundPatrolState();
        assertEquals("PATROL_TURN_START",RVP_GunnerGroundPolicy.patrol(input(0,0,0,false,true),SETTINGS,state,RANDOM).phase());
        var turn=RVP_GunnerGroundPolicy.patrol(input(1,0,0,false,true),SETTINGS,state,RANDOM);
        assertFalse(turn.forward()); assertTrue(turn.left());
        var launcher=new LauncherPositioningState();
        assertEquals("APPROACH",RVP_GunnerGroundPolicy.launcher(input(1,100,0,true,false),SETTINGS,launcher,RANDOM).phase());
        var parked=RVP_GunnerGroundPolicy.launcher(input(2,100,0,true,true),SETTINGS,launcher,RANDOM);
        assertEquals("LAUNCHER_PARK",parked.phase()); assertFalse(parked.forward());
    }

    @Test void targetScoreKeepsDistanceAndRadiansRatherThanDegrees() {
        assertEquals(132,RVP_GunnerTargetScore.score(100,1,false));
        assertEquals(100,RVP_GunnerTargetScore.score(100,Math.PI,true));
    }
}
