package org.ywzj.rvp.client.performance;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 从真实编译产物检查目录接入，防止只登记一个名字却没有任何生产计时入口。 */
class RVP_RenderPerformanceCoverageTest {
    @Test
    void everyCatalogStageIsUsedByAProductionRenderPathWithoutNewMixins() throws Exception {
        List<String> classes = List.of("performance/RVP_RenderPerformanceRecorder", "light/RVP_ScreenLightPass",
                "light/RVP_ScreenFrameResources", "light/RVP_RocketPlumePass", "light/RVP_RocketTrailPass",
                "light/RVP_SmokeLightPass", "light/RVP_VehicleExplosionPass", "visual/shockwave/RVP_PressureWavePass",
                "nuclear/RVP_NuclearShockwaveRenderer", "nuclear/RVP_NuclearVisualManager", "shader/RVP_NvgHandler",
                "shader/RVP_NvgGlowPass", "shader/TVMissileVideoPostHandler", "shader/RVP_CrtUiLiteHandler",
                "particle/RVP_ThermalParticleChannel", "gui/RVP_NvgGuiFallback", "gui/RVP_DircmHitlFilter",
                "gui/RVP_HitlVideoLossRenderer", "laser/RVP_LaserBlindOverlay", "visual/thermobaric/RVP_ThermobaricScreenFeedback",
                "compat/distanthorizons/RVP_DhDepthCompositeRenderer", "compat/distanthorizons/RVP_DhDepthCopy",
                "visual/shake/RVP_ScreenShakeExecutor");
        EnumSet<RVP_RenderStage> used = EnumSet.noneOf(RVP_RenderStage.class);
        for (String file : classes) {
            try (var input = getClass().getResourceAsStream("/org/ywzj/rvp/client/" + file + ".class")) {
                assertNotNull(input, file);
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                                if (opcode == Opcodes.GETSTATIC && owner.equals("org/ywzj/rvp/client/performance/RVP_RenderStage")) {
                                    used.add(RVP_RenderStage.valueOf(name));
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        assertEquals(EnumSet.allOf(RVP_RenderStage.class), used);
    }
}
