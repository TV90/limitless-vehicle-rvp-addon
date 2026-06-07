const missiles = ["$weapon2", "$weapon3", "$weapon4", "$weapon5"]

function updateBones(context) {
    const previousPropellerRotation = context.getFloat("propellerRotation", 0);
    const propellerRotation = (previousPropellerRotation + context.getPower() / 5) % 360;
    context.setFloat("propellerRotation", propellerRotation)

    const builder = createPoseBuilder();
    builder.setRotation("$blade0", 0, propellerRotation, 0);
    builder.setRotation("$blade1", propellerRotation, 0, 0);
    builder.setRotation("$blade2", propellerRotation, 0, 0);
    builder.setRotation("$weapon0", 0, -context.getPartYRot("auto_cannon"), 0);
    builder.setRotation("$weapon0_0", context.getPartXRot("auto_cannon"), 0, 0);
    let remainMissiles = context.getWeaponRemainAmmo("sighting_system", 1)
    for (let i = 0; i < missiles.length; i++) {
        if (i < missiles.length - remainMissiles) {
            builder.hideBone(missiles[i])
        }
    }
    return builder;
}
