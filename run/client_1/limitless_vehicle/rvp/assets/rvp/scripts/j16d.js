const pl10 = ["$weapon0", "$weapon1"]
const pl12_2 = ["$weapon2", "$weapon3"]
const yj91 = ["$weapon4"]
const gb1000 = ["$weapon5"]

function updateBones(context) {
    const pitchInput = context.getPitchInput()
    const yawInput = context.getYawInput()
    const rollInput = context.getRollInput()

    const builder = createPoseBuilder();
    builder.setRotation("$steering_wheel2", pitchInput * 16, 0, 0);
    builder.setRotation("$steering_wheel3", pitchInput * 16, 0, 0);
    builder.setRotation("$steering_wheel4", -rollInput * 16, 0, 0);
    builder.setRotation("$steering_wheel5", rollInput * 16, 0, 0);
    builder.setRotation("$steering_wheel0", 0, -yawInput * 16, 0);
    builder.setRotation("$steering_wheel1", 0, -yawInput * 16, 0);
    builder.setRotation("ctrl", -8 * pitchInput, 0, 8 * rollInput);

    const remainPl10 = Math.max(0, Math.min(pl10.length, context.getWeaponRemainAmmo("sighting_system", 0)))
    for (let i = 0; i < pl10.length; i++) {
        if (i < pl10.length - remainPl10) {
            builder.hideBone(pl10[i])
        }
    }

    const remainPl12_2 = Math.max(0, Math.min(pl12_2.length, context.getWeaponRemainAmmo("sighting_system", 1)))
    for (let i = 0; i < pl12_2.length; i++) {
        if (i < pl12_2.length - remainPl12_2) {
            builder.hideBone(pl12_2[i])
        }
    }

    const remainYj91 = Math.max(0, Math.min(yj91.length, context.getWeaponRemainAmmo("sighting_system", 2)))
    for (let i = 0; i < yj91.length; i++) {
        if (i < yj91.length - remainYj91) {
            builder.hideBone(yj91[i])
        }
    }

    const remainGb1000 = Math.max(0, Math.min(gb1000.length, context.getWeaponRemainAmmo("sighting_system", 3)))
    for (let i = 0; i < gb1000.length; i++) {
        if (i < gb1000.length - remainGb1000) {
            builder.hideBone(gb1000[i])
        }
    }

    return builder;
}
