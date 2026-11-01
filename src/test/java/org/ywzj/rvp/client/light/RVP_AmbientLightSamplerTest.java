package org.ywzj.rvp.client.light;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Group;
import org.ywzj.rvp.light.RVP_LightRuntimeTuning.Parameter;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实采样器的插值、缺区块回退、来源绑定与帧生命周期，不需要创建游戏世界。 */
class RVP_AmbientLightSamplerTest {
    @BeforeEach @AfterEach
    void resetTuning() {
        // 调用会话恢复，避免其它测试的环境适应覆盖影响查询次数。
        RVP_LightRuntimeTuning.reset();
    }

    @Test
    void interpolatesAllEightBlockCentersAndStaysContinuousAcrossBoundaries() {
        // 调用正式插值：八个角的数值不同，中心结果应为全部角的平均值。
        assertEquals(0.5F, RVP_AmbientLightSampler.interpolate(new Vec3(1, 1, 1),
                point -> (point.getX() + point.getY() * 2 + point.getZ() * 4) / 7.0), 0.000001F);
        float left = RVP_AmbientLightSampler.interpolate(new Vec3(1 - 0.00001, .5, .5),
                point -> point.getX() > 0 ? 1 : 0);
        float right = RVP_AmbientLightSampler.interpolate(new Vec3(1 + 0.00001, .5, .5),
                point -> point.getX() > 0 ? 1 : 0);
        assertEquals(0.5F, left, 0.00002F);
        assertEquals(left, right, 0.00003F);
    }

    @Test
    void unavailableCornerIsExplicitlyMissingButZeroWeightNeighborsAreNeverRead() {
        // 调用正式缺区块判断，不能把部分可用的天光和缺失方块光平均成虚假的暗处。
        assertEquals(-1, RVP_AmbientLightSampler.interpolate(new Vec3(1, 1, 1),
                point -> point.getX() == 1 ? -1 : .8));
        assertEquals(-1, RVP_AmbientLightSampler.interpolate(new Vec3(1, 1, 1), point -> Double.NaN));
        AtomicInteger calls = new AtomicInteger();
        assertEquals(.8F, RVP_AmbientLightSampler.interpolate(new Vec3(.5, .5, .5), point -> {
            calls.incrementAndGet();
            assertEquals(0, point.getX());
            return .8;
        }));
        assertEquals(1, calls.get());
        assertEquals(-1, RVP_AmbientLightSampler.interpolate(new Vec3(Double.NaN, 0, 0), point -> 1));
    }

    @Test
    void sameSourceIsCachedEvenWhenSmokeReordersTheLightSlots() {
        RVP_AmbientLightSampler sampler = new RVP_AmbientLightSampler();
        AtomicInteger calls = new AtomicInteger();
        // 调用正式帧初始化，以明暗分区模拟不同光源附近的真实环境。
        sampler.beginFrame(point -> {
            calls.incrementAndGet();
            return point.getX() < 8 ? .1 : .9;
        });
        Vec3 dark = new Vec3(.5, .5, .5), bright = new Vec3(16.5, .5, .5);
        assertEquals(.1F, sampler.sample(dark));
        assertEquals(.9F, sampler.sample(bright));
        // 模拟烟雾只留下亮处光，再反序查询；结果按来源位置而非槽号共享。
        assertEquals(.9F, sampler.sample(new Vec3(16.5, .5, .5)));
        assertEquals(.1F, sampler.sample(dark));
        assertEquals(2, calls.get());
    }

    @Test
    void nextFrameWorldChangesAndResourceCleanupDiscardOldSamples() {
        RVP_AmbientLightSampler sampler = new RVP_AmbientLightSampler();
        Vec3 source = new Vec3(.5, .5, .5);
        // 调用同一采样入口模拟夜晚到白天、卸载以及新世界，不能读上一帧亮度。
        sampler.beginFrame(point -> .2);
        assertEquals(.2F, sampler.sample(source));
        sampler.beginFrame(point -> 1);
        assertEquals(1, sampler.sample(source));
        sampler.clear();
        assertEquals(-1, sampler.sample(source));
        sampler.beginFrame(point -> .6);
        assertEquals(.6F, sampler.sample(source));
        sampler.beginFrame((ClientLevel) null, 0);
        assertEquals(-1, sampler.sample(source));
    }

    @Test
    void disabledAdaptationOrZeroWorldWeightDoesNotQueryWorld() {
        RVP_AmbientLightSampler sampler = new RVP_AmbientLightSampler();
        sampler.beginFrame(point -> { fail("关闭环境采样时不应读取区块"); return 1; });
        // 调用真正的指令及面板共用参数写入，确认两个开关条件都能省去世界查询。
        RVP_LightRuntimeTuning.set(Group.global, Parameter.ambientEnabled, 0);
        assertEquals(-1, sampler.sample(Vec3.ZERO));
        RVP_LightRuntimeTuning.set(Group.global, Parameter.ambientEnabled, 1);
        RVP_LightRuntimeTuning.set(Group.global, Parameter.ambientWorldWeight, 0);
        assertEquals(-1, sampler.sample(Vec3.ZERO));
    }
}
