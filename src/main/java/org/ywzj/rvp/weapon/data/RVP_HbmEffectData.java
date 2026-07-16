package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

public class RVP_HbmEffectData {

    @SerializedName("enabled")
    private boolean enabled = false;

    @SerializedName("real_explosion")
    private String realExplosion = "none";

    @SerializedName("visual_preset")
    private String visualPreset = "none";

    @SerializedName("visual_scale")
    private float visualScale = 1.0f;

    @SerializedName("visual_density")
    private float visualDensity = 1.0f;

    @SerializedName("visual_backend")
    private String visualBackend = "auto";

    @SerializedName("visual_sound")
    private boolean visualSound = true;

    @SerializedName("suppress_native_explosion_effect")
    private boolean suppressNativeExplosionEffect = true;

    @SerializedName("nuclear_sound")
    private boolean nuclearSound = true;

    @SerializedName("nuclear_flash")
    private boolean nuclearFlash = true;

    @SerializedName("nuclear_shake")
    private boolean nuclearShake = true;

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

    public float getVisualScale() {
        return Math.max(visualScale, 0.1f);
    }

    public float getVisualDensity() {
        return Math.max(0.1f, Math.min(visualDensity, 1.0f));
    }

    public String getVisualBackend() {
        String backend = visualBackend == null ? "auto" : visualBackend.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (backend) {
            case "hbm", "rvp" -> backend;
            default -> "auto";
        };
    }

    public boolean isNuclearSound() {
        return nuclearSound;
    }

    public boolean isVisualSound() {
        return visualSound;
    }

    public boolean isSuppressNativeExplosionEffect() {
        return suppressNativeExplosionEffect;
    }

    public boolean isNuclearFlash() {
        return nuclearFlash;
    }

    public boolean isNuclearShake() {
        return nuclearShake;
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
