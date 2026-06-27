function updateBones(context) {
    const pose = createPoseBuilder();
    const v = context.getEntity();

    if (!v.rvp_isEraActive("ERA0")) pose.hideBone("$ERA0");
    if (!v.rvp_isEraActive("ERA1")) pose.hideBone("$ERA1");
    if (!v.rvp_isEraActive("ERA2")) pose.hideBone("$ERA2");
    if (!v.rvp_isEraActive("ERA3")) pose.hideBone("$ERA3");
    if (!v.rvp_isEraActive("ERA4")) pose.hideBone("$ERA4");
    if (!v.rvp_isEraActive("ERA5")) pose.hideBone("$ERA5");
    if (!v.rvp_isEraActive("ERA6")) pose.hideBone("$ERA6");
    if (!v.rvp_isEraActive("ERA7")) pose.hideBone("$ERA7");
    if (!v.rvp_isEraActive("ERA8")) pose.hideBone("$ERA8");
    if (!v.rvp_isEraActive("ERA9")) pose.hideBone("$ERA9");

    return pose;
}
