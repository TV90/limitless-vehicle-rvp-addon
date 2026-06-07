function updateBones(context) {
    const pitchInput = context.getPitchInput()
    const yawInput = context.getYawInput()
    const rollInput = context.getRollInput()

    const builder = createPoseBuilder();
    builder.setRotation("flw", -pitchInput * 16, 0, 0);
    builder.setRotation("frw", -pitchInput * 16, 0, 0);
    builder.setRotation("tlw2", pitchInput * 16, 0, 0);
    builder.setRotation("trw2", pitchInput * 16, 0, 0);
    builder.setRotation("lw", -rollInput * 16, 0, 0);
    builder.setRotation("rw", rollInput * 16, 0, 0);
    builder.setRotation("tlw", 0, -yawInput * 16, 0);
    builder.setRotation("trw", 0, -yawInput * 16, 0);
    builder.setRotation("ctrl", -8 * pitchInput, 0, 8 * rollInput);
    return builder;
}
