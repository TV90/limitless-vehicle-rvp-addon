package org.ywzj.rvp.weapon.data;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RVP_FireDataTest {

    private final Gson gson = new Gson();

    @Test
    void requireLockDefaultsToTrueAndCanBeDisabledInFireData() {
        assertTrue(gson.fromJson("{}", RVP_FireData.class).isRequireLock());
        assertFalse(gson.fromJson("{\"require_lock\":false}", RVP_FireData.class).isRequireLock());
    }
}
