package org.ywzj.rvp.entity.gunner.ai;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.entity.gunner.ai.RVP_GunnerGroundPolicy.*;

import static org.junit.jupiter.api.Assertions.*;

/** 验证抽取前的重要地面行为合同，包括同时递减的战术计时与脱困抢占。 */
class RVP_GunnerGroundPolicyTest {
    /** 使用原默认数值的参数夹具。 */
    private static final Settings SETTINGS = new Settings(12,100,140,140,55,20,1,20,300,300,120,120,40,
            0.2,1.35,0.35,300,6);
    /** 固定随机接口，避免测试依赖概率。 */
    private static final RandomInput RANDOM = new RandomInput() {
        @Override public int nextInt(int bound) { return 0; }
        @Override public float nextFloat() { return 0; }
        @Override public boolean nextBoolean() { return true; }
    };

    /** 目标在车前，yaw 改变用于检查角度边界。 */
    private static Input input(long tick, double distance, float yaw, boolean launcher, boolean ammo) {
        return input(tick, distance, yaw, launcher, ammo, false);
    }

    /** 带换弹状态的输入；reloading=true 时主战武器处于装填窗口。 */
    private static Input input(long tick, double distance, float yaw, boolean launcher, boolean ammo, boolean reloading) {
        return new Input(tick,new Point(0,0),yaw,-Math.sin(Math.toRadians(yaw)),Math.cos(Math.toRadians(yaw)),
                new Point(0,distance),false,reloading,launcher,ammo);
    }

    /** 生物目标输入：验证生物与载具使用不同停车距离。 */
    private static Input creatureInput(long tick, double distance, float yaw, boolean reloading) {
        return new Input(tick,new Point(0,0),yaw,-Math.sin(Math.toRadians(yaw)),Math.cos(Math.toRadians(yaw)),
                new Point(0,distance),true,reloading,false,true);
    }

    @Test void standoffHoldsStillWithoutSideEvade() {
        var state=new GroundEngagementState();
        for (int tick=0;tick<=60;tick++) {
            state.tick();
            var command=RVP_GunnerGroundPolicy.engage(input(tick,10,0,false,true),SETTINGS,state,RANDOM);
            assertEquals("HOLD",command.phase(),"tick="+tick);
            assertFalse(command.forward());
            assertFalse(command.backward());
        }
        var lost=new Input(61,new Point(0,0),0,0,1,null,false,false,false,true);
        assertEquals("NO_TARGET",RVP_GunnerGroundPolicy.engage(lost,SETTINGS,state,RANDOM).phase());
        assertTrue(RVP_GunnerGroundPolicy.engage(input(62,100,0,false,true),SETTINGS,state,RANDOM).forward());
    }

    /** 目标主动贴脸：进入驻车带下沿以内倒车，期间保持对敌转向。 */
    @Test void backsOffWhenTargetClosesInsideStandoff() {
        var state=new GroundEngagementState();
        var back=RVP_GunnerGroundPolicy.engage(input(1,5,0,false,true),SETTINGS,state,RANDOM);
        assertEquals("BACK_OFF",back.phase());
        assertTrue(back.backward());
        assertFalse(back.forward());
        // 倒车输入左右与前进相反：目标在右（yawDelta=+10）时倒车应给 left，车头才转向目标。
        var steered=RVP_GunnerGroundPolicy.engage(input(2,5,-10,false,true),SETTINGS,state,RANDOM);
        assertEquals("BACK_OFF",steered.phase());
        assertTrue(steered.backward());
        assertTrue(steered.left());
        assertFalse(steered.right());
        // 目标在车尾：禁止倒车（否则车尾朝目标倒退贴近），原地转向。
        var turned=RVP_GunnerGroundPolicy.engage(input(3,5,180,false,true),SETTINGS,state,RANDOM);
        assertEquals("HOLD",turned.phase());
        assertFalse(turned.backward());
        assertTrue(turned.left() || turned.right());
        // 回到驻车带内后停止倒车。
        var hold=RVP_GunnerGroundPolicy.engage(input(4,10,0,false,true),SETTINGS,state,RANDOM);
        assertEquals("HOLD",hold.phase());
        assertFalse(hold.backward());
    }

    /** 生物使用更近的停车阈值，且装填期不倒车；载具目标维持原距离与换弹倒车。 */
    @Test void creatureTargetUsesCloserStopDistanceAndSkipsReloadReverse() {
        var vehicle = RVP_GunnerGroundPolicy.engage(input(1,5,0,false,true),SETTINGS,
                new GroundEngagementState(),RANDOM);
        assertEquals("BACK_OFF",vehicle.phase());
        var creatureState = new GroundEngagementState();
        var creature = RVP_GunnerGroundPolicy.engage(creatureInput(1,5,0,false),SETTINGS,creatureState,RANDOM);
        assertNotEquals("BACK_OFF",creature.phase());
        assertFalse(creature.backward());
        var reload = RVP_GunnerGroundPolicy.engage(creatureInput(2,5,0,true),SETTINGS,creatureState,RANDOM);
        assertEquals("RELOAD_HOLD",reload.phase());
        assertFalse(reload.backward());
    }

    /** 载具切生物时必须按生物阈值重抽驻车点，不能沿用载具的 60 格旧驻车点。 */
    @Test void switchingFromVehicleToCreatureRefreshesStandoff() {
        Settings wide = new Settings(60,100,140,140,55,20,1,20,300,600,120,180,40,
                0.2,1.35,0.35,300,6);
        var state = new GroundEngagementState();
        var vehicle = RVP_GunnerGroundPolicy.engage(input(1,30,0,false,true),wide,state,RANDOM);
        assertEquals("BACK_OFF",vehicle.phase());
        var creature = RVP_GunnerGroundPolicy.engage(creatureInput(2,30,0,false),wide,state,RANDOM);
        assertNotEquals("BACK_OFF",creature.phase());
        assertTrue(creature.forward());
    }

    /** 连续倒车超过疲劳上限后必须打断为一段带偏航的前进。 */
    @Test void continuousBackOffBreaksIntoForwardTurn() {
        var state = new GroundEngagementState();
        int breakTick = -1;
        for (int tick = 1; tick <= 120; tick++) {
            var command = RVP_GunnerGroundPolicy.engage(input(tick,5,0,false,true),SETTINGS,state,RANDOM);
            if ("REVERSE_BREAK".equals(command.phase())) {
                assertTrue(command.forward());
                assertFalse(command.backward());
                assertTrue(command.left() || command.right());
                breakTick = tick;
                break;
            }
            assertEquals("BACK_OFF",command.phase(),"tick="+tick);
        }
        assertTrue(breakTick > 0);
        var continued = RVP_GunnerGroundPolicy.engage(
                input(breakTick + 1,5,0,false,true),SETTINGS,state,RANDOM);
        assertEquals("REVERSE_BREAK",continued.phase());
        assertTrue(continued.forward());
        assertFalse(continued.backward());
    }

    @Test void steeringAndForwardKeepTheirStrictAngleBoundaries() {        assertFalse(RVP_GunnerGroundPolicy.engage(input(1,100,-8,false,true),SETTINGS,new GroundEngagementState(),RANDOM).right());
        assertTrue(RVP_GunnerGroundPolicy.engage(input(1,100,-8.1f,false,true),SETTINGS,new GroundEngagementState(),RANDOM).right());
        assertFalse(RVP_GunnerGroundPolicy.engage(input(1,100,80,false,true),SETTINGS,new GroundEngagementState(),RANDOM).forward());
        assertTrue(RVP_GunnerGroundPolicy.engage(input(1,100,79.9f,false,true),SETTINGS,new GroundEngagementState(),RANDOM).forward());
        assertFalse(RVP_GunnerGroundPolicy.engage(input(1,10,0,false,true),SETTINGS,new GroundEngagementState(),RANDOM).forward());
    }

    /** 换弹期战术倒车：按随机倒车距离拉开，装填完成后恢复接敌，全程车头保持对敌。 */
    @Test void reloadReversesOutThenHoldsAndResumesApproach() {
        var state=new GroundEngagementState();
        var reverse=RVP_GunnerGroundPolicy.engage(
                input(1,SETTINGS.stopDistance(),0,false,true,true),SETTINGS,state,RANDOM);
        assertEquals("RELOAD_REVERSE",reverse.phase());
        assertTrue(reverse.backward());
        assertFalse(reverse.forward());
        // 倒车时左右符号反转：目标在右（yawDelta=+10）时倒车应给 left。
        var steered=RVP_GunnerGroundPolicy.engage(
                input(2,SETTINGS.stopDistance(),-10,false,true,true),SETTINGS,state,RANDOM);
        assertEquals("RELOAD_REVERSE",steered.phase());
        assertTrue(steered.backward());
        assertTrue(steered.left());
        assertFalse(steered.right());
        // 目标在车后：禁止倒车（否则车尾朝目标倒退贴近），原地转向等车头对准。
        var turned=RVP_GunnerGroundPolicy.engage(
                input(3,SETTINGS.stopDistance(),180,false,true,true),SETTINGS,state,RANDOM);
        assertEquals("RELOAD_HOLD",turned.phase());
        assertFalse(turned.backward());
        assertTrue(turned.left() || turned.right());
        // 超过随机倒车距离后停止倒车，原地保持并继续对敌转向
        var hold=RVP_GunnerGroundPolicy.engage(
                input(3,SETTINGS.stopDistance()*1.8,90,false,true,true),SETTINGS,state,RANDOM);
        assertEquals("RELOAD_HOLD",hold.phase());
        assertFalse(hold.forward());
        assertFalse(hold.backward());
        assertTrue(hold.left() || hold.right());
        // 装填完成后目标仍远：恢复前进接敌
        var approach=RVP_GunnerGroundPolicy.engage(
                input(4,100,0,false,true,false),SETTINGS,state,RANDOM);
        assertEquals("APPROACH",approach.phase());
        assertTrue(approach.forward());
    }

    /** 驻车带会随随机源变化，且换弹倒车不再固定倒到旧的 1.8× 停车距离。 */
    @Test void standoffBandAndReloadRetreatAreRandomized() {
        Settings wide = new Settings(60,100,140,140,55,20,1,20,300,600,120,180,40,
                0.2,1.35,0.35,300,12);
        RandomInput low = new RandomInput() {
            @Override public int nextInt(int bound) { return 0; }
            @Override public float nextFloat() { return 0.0F; }
            @Override public boolean nextBoolean() { return true; }
        };
        RandomInput high = new RandomInput() {
            @Override public int nextInt(int bound) { return 0; }
            @Override public float nextFloat() { return 0.999F; }
            @Override public boolean nextBoolean() { return true; }
        };
        assertEquals("APPROACH", RVP_GunnerGroundPolicy.engage(
                input(1,70,0,false,true), wide, new GroundEngagementState(), low).phase());
        assertEquals("HOLD", RVP_GunnerGroundPolicy.engage(
                input(1,70,0,false,true), wide, new GroundEngagementState(), high).phase());
        assertEquals("RELOAD_HOLD", RVP_GunnerGroundPolicy.engage(
                input(1,90,0,false,true,true), wide, new GroundEngagementState(), low).phase());
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

    /** 发射架在无弹贴近时仍保留原 HOLD→EVADE 节奏（本次只改非发射架地面接敌）。 */
    @Test void launcherKeepsOriginalHoldThenEvadeCadence() {
        var state=new LauncherPositioningState();
        assertEquals("HOLD",RVP_GunnerGroundPolicy.launcher(input(0,10,0,true,false),SETTINGS,state,RANDOM).phase());
        for (int tick=1;tick<=140;tick++) {
            state.tick();
            var command=RVP_GunnerGroundPolicy.launcher(input(tick,10,0,true,false),SETTINGS,state,RANDOM);
            assertEquals(tick<100 || tick==140 ? "HOLD" : "EVADE",command.phase(),"tick="+tick);
        }
    }

    @Test void targetScoreKeepsDistanceAndRadiansRatherThanDegrees() {
        assertEquals(132,RVP_GunnerTargetScore.score(100,1,false));
        assertEquals(100,RVP_GunnerTargetScore.score(100,Math.PI,true));
    }
}
