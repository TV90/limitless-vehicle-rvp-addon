const aim120 = ["$weapon2", "$weapon3", "$weapon4", "$weapon5"]
const spice1000 = ["$weapon0", "$weapon1"]

function updateBones(context) {
    const pitchInput = context.getPitchInput()
    const yawInput = context.getYawInput()
    const rollInput = context.getRollInput()

    const builder = createPoseBuilder();
    builder.setRotation("$steering_wheel0", -rollInput * 16, 0, 0);
    builder.setRotation("$steering_wheel1", rollInput * 16, 0, 0);
    builder.setRotation("tlw", pitchInput * 16, 0, 0);
    builder.setRotation("trw", pitchInput * 16, 0, 0);
    builder.setRotation("tw", 0, -yawInput * 5, 0);
    builder.setRotation("ctrl", -14 * pitchInput, 0, 14 * rollInput);

    const remainAim120 = Math.max(0, Math.min(aim120.length, context.getWeaponRemainAmmo("sighting_system", 1)))
    for (let i = 0; i < aim120.length; i++) {
        if (i < aim120.length - remainAim120) {
            builder.hideBone(aim120[i])
        }
    }

    const remainSpice1000 = Math.max(0, Math.min(spice1000.length, context.getWeaponRemainAmmo("sighting_system", 2)))
    for (let i = 0; i < spice1000.length; i++) {
        if (i < spice1000.length - remainSpice1000) {
            builder.hideBone(spice1000[i])
        }
    }
    return builder;
}
