package org.ywzj.rvp.client.tuning;

import org.junit.jupiter.api.Test;
import org.ywzj.rvp.client.performance.RVP_RenderPerformance;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** F8保存器只能保存统计间隔，不能偷偷把性能记录开关保存成下次自动开启。 */
class RVP_RenderPerformanceTuningModuleTest {
    @Test
    void savingOrRestoringTuningNeverStartsProfiling() {
        RVP_TuningRegistry registry = new RVP_TuningRegistry();
        registry.register(new RVP_RenderPerformanceTuningModule()); registry.seal();
        assertEquals(1, registry.options().size()); assertEquals(3, registry.modules().get(0).groups().get(0).actions().size());
        assertFalse(RVP_RenderPerformance.enabled()); assertTrue(registry.snapshot().isEmpty());
        var option = registry.options().get("render_performance/recording/intervalSeconds");
        try {
            option.set(4); assertEquals(4, RVP_RenderPerformance.intervalSeconds());
            var saved = registry.snapshot(); registry.restore(Map.of()); registry.restore(saved);
            assertEquals(4, RVP_RenderPerformance.intervalSeconds()); assertFalse(RVP_RenderPerformance.enabled());
            assertThrows(IllegalArgumentException.class, () -> option.set(Double.NaN));
        } finally { option.reset(); }
    }
}
