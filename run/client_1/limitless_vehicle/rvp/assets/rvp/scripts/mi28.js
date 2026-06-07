const kh39tMissiles = ["$weapon1", "$weapon2"]
const kh39TvMissiles = ["$weapon3", "$weapon4"]

function updateBones(context) {
    const previousPropellerRotation = context.getFloat("propellerRotation", 0);
    const propellerRotation = (previousPropellerRotation + context.getPower() / 5) % 360;
    context.setFloat("propellerRotation", propellerRotation)

    const builder = createPoseBuilder();
    builder.setRotation("$blade0", 0, propellerRotation, 0);
    builder.setRotation("$blade1", propellerRotation, 0, 0);
    builder.setRotation("$blade2", -propellerRotation, 0, 0);
    builder.setRotation("$weapon0", 0, -context.getPartYRot("auto_cannon"), 0);
    builder.setRotation("$weapon0_0", context.getPartXRot("auto_cannon"), 0, 0);

    let remainKh39t = context.getWeaponRemainAmmo("sighting_system", 1)
    for (let i = 0; i < kh39tMissiles.length; i++) {
        if (i < kh39tMissiles.length - remainKh39t) {
            builder.hideBone(kh39tMissiles[i])
        }
    }

    let remainKh39Tv = context.getWeaponRemainAmmo("sighting_system", 2)
    for (let i = 0; i < kh39TvMissiles.length; i++) {
        if (i < kh39TvMissiles.length - remainKh39Tv) {
            builder.hideBone(kh39TvMissiles[i])
        }
    }
    return builder;
}
