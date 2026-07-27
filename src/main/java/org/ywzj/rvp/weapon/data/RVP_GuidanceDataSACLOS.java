package org.ywzj.rvp.weapon.data;

import com.google.gson.annotations.SerializedName;

public class RVP_GuidanceDataSACLOS extends RVP_GuidanceData {

    @SerializedName("semi_correction_enabled")
    private boolean semiCorrectionEnabled = false;

    @SerializedName("semi_correction_stiffness")
    private float semiCorrectionStiffness = 0.05f;

    @SerializedName("semi_correction_damping")
    private float semiCorrectionDamping = 0.05f;

    @SerializedName("semi_correction_wobble")
    private float semiCorrectionWobble = 0.5f;

    public boolean isSemiCorrectionEnabled() {
        return semiCorrectionEnabled;
    }

    public float getSemiCorrectionStiffness() {
        return Math.max(semiCorrectionStiffness, 0f);
    }

    public float getSemiCorrectionDamping() {
        return Math.max(semiCorrectionDamping, 0f);
    }

    public float getSemiCorrectionWobble() {
        return Math.max(semiCorrectionWobble, 0f);
    }
}
