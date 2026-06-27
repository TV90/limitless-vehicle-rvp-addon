/**
 * PS1SM 铠甲自行防空炮 — 动画骨骼脚本
 *
 * 动画（在 ps1sm_controller.json 中配置）：
 *   auto_gun         — 机炮开火后坐力动画（event_animation）
 *   lock_radar       — 锁定雷达折叠动画（switchable_animation）
 *   scan_radar       — 搜索雷达连续 360° 旋转（switchable_animation，loop）
 */

function updateBones(context) {
    return createPoseBuilder();
}
