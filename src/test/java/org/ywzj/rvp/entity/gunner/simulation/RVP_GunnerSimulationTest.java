package org.ywzj.rvp.entity.gunner.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 多刻集成回归：生产算法、受阻环境、感知变化与实际导出合同一起验证。 */
class RVP_GunnerSimulationTest {
    /** 场景资源根目录。 */ private static final Path SCENES=Path.of("src/test/resources/gunner-simulation");
    /** 测试输出临时目录，避免覆盖用户回放。 */ @TempDir Path output;

    /** 使用命令行相同解析入口。 */
    private static RVP_GunnerSimulation.Replay run(String id) throws Exception {
        return new RVP_GunnerSimulation().run(RVP_GunnerSimulationMain.read(SCENES.resolve(id+".json")));
    }

    @Test void fixedSeedProducesIdenticalReplayIncludingEvents() throws Exception {
        var first=run("02_encounter"); var second=run("02_encounter");
        assertEquals(RVP_GunnerSimulationMain.JSON.toJson(first),RVP_GunnerSimulationMain.JSON.toJson(second));
        assertEquals(first.scene().ticks+1,first.frames().size());
        assertEquals(0,first.frames().get(0).tick());
        assertEquals(first.scene().ticks,first.frames().get(first.frames().size()-1).tick());
        assertTrue(first.frames().stream().flatMap(f->f.events().stream()).anyMatch(e->e.type().equals("TARGET") && e.tick()>=160 && e.tick()<700));
        assertTrue(first.frames().stream().filter(f->f.tick()>=700).allMatch(f->f.actors().get(0).targetId()==null));
        assertTrue(first.frames().stream().noneMatch(f->"friendly".equals(f.actors().get(0).targetId())));
    }

    @Test void wallBlocksMotionAndCurrentAlgorithmActuallyAttemptsRecovery() throws Exception {
        var replay=run("03_blocked");
        assertTrue(replay.summary().blockedTicks()>0);
        assertTrue(replay.summary().recoveries()>0);
        var world=new RVP_GunnerSimulationWorld(replay.scene());
        var actor=replay.scene().actors.get(0);
        for (var frame:replay.frames()) {
            var sample=frame.actors().get(0);
            assertFalse(world.blocked(sample.position(),sample.position(),actor.radius,actor.height),"tick="+frame.tick());
            if (sample.phase().equals("RECOVERY")) {
                assertEquals("stuck_recovery",sample.behavior()); assertTrue(sample.command().backward());
                assertTrue(sample.diagnostics().stream().anyMatch(s->s.contains("ground_engagement_move")));
            }
        }
    }

    @Test void collisionChecksSweptVolumeAndVisibilityNotOnlyEndpoint() {
        double[] min={5,0,-1},max={5.01,10,1};
        assertTrue(RVP_GunnerSimulationWorld.intersects(new double[]{0,2,0},new double[]{20,2,0},min,max));
        assertFalse(RVP_GunnerSimulationWorld.intersects(new double[]{0,20,0},new double[]{20,20,0},min,max));
        assertTrue(RVP_GunnerSimulationWorld.intersects(new double[]{5.005,2,0},new double[]{5.005,2,0},min,max));
    }

    @Test void launcherStopsWhenAmmoReturnsAndPatrolNeverInventsAnEnemy() throws Exception {
        var launcher=run("04_launcher");
        for (var f:launcher.frames()) if (f.tick()<120 || f.tick()>=520) {
            var sample=f.actors().get(0);
            assertEquals("LAUNCHER_PARK",sample.phase()); assertFalse(sample.command().forward());
        }
        var patrol=run("01_patrol");
        assertTrue(patrol.frames().stream().allMatch(f->f.actors().get(0).targetId()==null));
        assertTrue(patrol.summary().distance().get("blue")>10);
    }

    @Test void publicFlightRunsForTwoMinutesAndRecordsChangingMissions() throws Exception {
        var flight=run("05_fixed_wing"); Set<String> phases=new HashSet<>();
        for (var frame:flight.frames()) {
            var a=frame.actors().get(0); phases.add(a.phase());
            for (double n:a.position()) assertTrue(Double.isFinite(n));
            assertTrue(Math.abs(a.command().pitch())<=1 && Math.abs(a.command().roll())<=1);
            assertTrue(a.position()[1]>=flight.scene().groundY);
        }
        assertTrue(phases.contains("PATROL"));
        assertTrue(phases.contains("ATTACK") || phases.contains("APPROACH"),phases.toString());
        assertTrue(phases.contains("DISENGAGE") || phases.contains("RETURN"),phases.toString());
        assertTrue(flight.summary().distance().get("blue")>1000);
    }

    @Test void commandLineWritesReadableIndexAndRejectsInvalidScene() throws Exception {
        RVP_GunnerSimulationMain.main(new String[]{SCENES.resolve("01_patrol.json").toString(),output.toString()});
        var index=RVP_GunnerSimulationMain.JSON.fromJson(Files.readString(output.resolve("index.json")),RVP_GunnerSimulationMain.Index.class);
        assertEquals("01_patrol.json",index.replays().get(0).file());
        var replay=RVP_GunnerSimulationMain.JSON.fromJson(Files.readString(output.resolve("01_patrol.json")),RVP_GunnerSimulation.Replay.class);
        assertEquals(801,replay.frames().size());
        var scene=RVP_GunnerSimulationMain.read(SCENES.resolve("01_patrol.json"));
        scene.actors.get(0).scanInterval=0;
        assertThrows(IllegalArgumentException.class,scene::validate);
        scene.actors.get(0).scanInterval=10; scene.schemaVersion=0;
        assertThrows(IllegalArgumentException.class,scene::validate);
    }
}
