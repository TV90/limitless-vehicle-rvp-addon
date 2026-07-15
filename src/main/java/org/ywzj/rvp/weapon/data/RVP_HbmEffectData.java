package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

public class RVP_HbmEffectData {

    @SerializedName("enabled")
    private boolean enabled = false;

    @SerializedName("real_explosion")
    private String realExplosion = "none";

    @SerializedName("visual_preset")
    private String visualPreset = "none";

    @SerializedName("effect_yield")
    private float effectYield = 0.0f;

    @SerializedName("spawn_frag")
    private boolean spawnFrag = false;

    @SerializedName("white_phosphorus")
    private boolean whitePhosphorus = false;

    @SerializedName("chlorine_yield")
    private float chlorineYield = 0.0f;

    @SerializedName("destroy_block")
    private boolean destroyBlock = true;

    public boolean isEnabled() {
        return enabled;
    }

    public String getRealExplosion() {
        return realExplosion == null ? "none" : realExplosion.trim();
    }

    public String getVisualPreset() {
        return visualPreset == null ? "none" : visualPreset.trim();
    }

    public float getEffectYield() {
        return Math.max(effectYield, 0.0f);
    }

    public boolean isSpawnFrag() {
        return spawnFrag;
    }

    public boolean isWhitePhosphorus() {
        return whitePhosphorus;
    }

    public float getChlorineYield() {
        return Math.max(chlorineYield, 0.0f);
    }

    public boolean isDestroyBlock() {
        return destroyBlock;
    }

    public boolean hasRealExplosion() {
        return isEnabled() && !"none".equalsIgnoreCase(getRealExplosion());
    }

    public boolean hasVisualPreset() {
        return isEnabled() && !"none".equalsIgnoreCase(getVisualPreset());
    }

    public boolean hasFragEffect() {
        return isEnabled() && spawnFrag;
    }

    public boolean hasWhitePhosphorus() {
        return isEnabled() && whitePhosphorus;
    }

    public boolean hasChlorineEffect() {
        return isEnabled() && getChlorineYield() > 0.0f;
    }

    public boolean hasAnyEffect() {
        return hasRealExplosion() || hasVisualPreset()
                || hasFragEffect() || hasWhitePhosphorus() || hasChlorineEffect();
    }
}
