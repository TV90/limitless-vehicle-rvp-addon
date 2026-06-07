# Mi-28 S13 边界测试挂架（人在回路 TV 弹）

10 枚测试弹均开启 `guidance_data.human_in_the_loop`，发射后自动进入弹载视角；导弹销毁或右键退出后恢复原先观瞄视角。

| 挂架 | 名称 | 底层制导 | HITL 操控 |
|------|------|----------|-----------|
| t01 | ARH+人在回路 | ARH | VIEW（仅跟弹） |
| t02 | IR+人在回路 | IR | VIEW |
| t03 | SARH+人在回路 | SARH | VIEW |
| t04 | MCLOS+人在回路 | MCLOS | MOUSE（鼠标驾控，战地3/4） |
| t05 | ARM+人在回路 | ARM | VIEW |
| t06 | 电视驾控弹 | MCLOS | MOUSE |
| t07 | GPS+人在回路 | GPS | VIEW |
| t08 | SACLOS+人在回路 | SACLOS | DESIGNATE（左键点选，战地2） |
| t09 | IOG+人在回路 | IOG | VIEW |
| t10 | GPS+末段IR | GPS→IR | VIEW |

**操作**：`4` 切换画面模式；右键退出弹载视角；T08 左键在画面上点选目标。

JSON 路径：`weapons/mi28_s13_t*.json`（已同步至 `run/client_1/limitless_vehicle/rvp/data/rvp/weapons/`）。
