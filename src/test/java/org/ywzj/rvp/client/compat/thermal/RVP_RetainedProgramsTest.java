package org.ywzj.rvp.client.compat.thermal;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RVP_RetainedProgramsTest {
    @Test
    void repeatedFramesReuseIdenticalProgramsWithoutDeleting() {
        Object program = new Object();
        Map<Object, Object> table = new HashMap<>();
        table.put("solid", program);
        table.put("unusedShadow", null);
        for (int i = 0; i < 100; i++) {
            var lease = new RVP_RetainedPrograms(table, value -> fail("正常退出不应销毁程序"));
            assertTrue(table.isEmpty());
            assertTrue(lease.restore(true));
            assertSame(program, table.get("solid"));
            assertTrue(table.containsKey("unusedShadow"));
            assertFalse(lease.restore(false));
        }
    }

    @Test
    void reloadReleasesHiddenProgramsOnceWithoutOverwritingNewGeneration() {
        Object old = new Object(), fresh = new Object();
        Map<Object, Object> table = new HashMap<>();
        table.put("solid", old);
        table.put("cutout", old);
        var deleted = new ArrayList<Object>();
        var lease = new RVP_RetainedPrograms(table, deleted::add);
        table.put("solid", fresh);
        assertFalse(lease.restore(false));
        assertSame(fresh, table.get("solid"));
        assertEquals(java.util.List.of(old), deleted);
        assertFalse(lease.restore(false));
        assertEquals(1, deleted.size());
    }

    @Test
    void externalChangesPreserveProgramsStillOwnedByNewTable() {
        Object shared = new Object(), obsolete = new Object();
        Map<Object, Object> table = new HashMap<>();
        table.put("solid", shared);
        table.put("cutout", obsolete);
        var deleted = new ArrayList<Object>();
        var lease = new RVP_RetainedPrograms(table, deleted::add);
        table.put("newSolid", shared);
        assertFalse(lease.restore(true));
        assertSame(shared, table.get("newSolid"));
        assertEquals(java.util.List.of(obsolete), deleted);
    }
}
