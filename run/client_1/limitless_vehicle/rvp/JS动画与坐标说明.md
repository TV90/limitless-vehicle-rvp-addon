function updateBones(context)
;脚本入口函数名；控制器会每帧调用它来更新骨骼

context
;模组提供的运行时上下文；用来读取当前载具的操纵输入/状态

const pitchInput = context.getPitchInput()
;俯仰输入（拉/推）；通常约 -1 ~ +1

const yawInput = context.getYawInput()
;偏航输入（方向舵）；通常约 -1 ~ +1

const rollInput = context.getRollInput()
;滚转输入（左右滚）；通常约 -1 ~ +1

const builder = createPoseBuilder()
;创建姿态构建器；把“哪个骨骼转多少”写进去，最后 return

builder.setRotation("flw", -pitchInput * 16, 0, 0)
;设置骨骼 flw 的旋转角度（单位：度）；-pitchInput 表示方向反过来；*16 表示最大约 16°

builder.setRotation("frw", -pitchInput * 16, 0, 0)
;同上，另一侧对称骨骼

builder.setRotation("tlw2", pitchInput * 16, 0, 0)
;设置骨骼 tlw2 随 pitch 同向偏转（最大约 16°）

builder.setRotation("trw2", pitchInput * 16, 0, 0)
;同上，另一侧对称骨骼

builder.setRotation("lw", -rollInput * 16, 0, 0)
;设置骨骼 lw 随 roll 偏转；左右通常一正一负以形成滚转

builder.setRotation("rw", rollInput * 16, 0, 0)
;设置骨骼 rw 随 roll 偏转（与 lw 方向相反）

builder.setRotation("tlw", 0, -yawInput * 16, 0)
;设置骨骼 tlw 随 yaw 偏转；这里绕本地 Y 轴转；负号决定方向

builder.setRotation("trw", 0, -yawInput * 16, 0)
;同上，另一侧对称骨骼

builder.setRotation("ctrl", -8 * pitchInput, 0, 8 * rollInput)
;综合控制骨骼 ctrl：同时受 pitch/roll 影响；用 8 而不是 16 表示幅度更小

return builder
;把本帧需要应用的骨骼姿态返回给模组
