---
name: "ywzj-game-pack-root"
description: "记录当前用于测试的游戏载具包根目录并约定默认操作路径。Invoke when user says 游戏目录/载具包目录/让我改游戏目录文件 or when editing rvp pack resources."
---

# YWZJ Game Pack Root

## 根目录（默认）

`E:\client_ywzj\ywzj\.minecraft\versions\Optimized fps\limitless_vehicle\rvp\`

## 常用子目录速查

- 车辆数据：`data/rvp/vehicles/`
- 武器数据：`data/rvp/weapons/`
- 配方：`data/rvp/recipes/`（有些版本也会读 `data/rvp/recipe/`）
- 车辆显示：`assets/rvp/display/vehicle/`
- 武器显示（含发射/换弹声音）：`assets/rvp/display/weapon/`
- 动画控制器：`assets/rvp/animation_controllers/`
- 脚本：`assets/rvp/scripts/`
- 语言：`assets/rvp/lang/`

## 约定

- 用户说“游戏目录/载具包目录”但未给路径时，默认指向本 skill 的根目录。
- 需要同时修改“数据(data)”和“资源(assets)”时，都以该根目录为准进行增删改。
