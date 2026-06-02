---
name: "ywzj-rvp-param-doc-sync"
description: "Keeps RVP addon parameters documented. Invoke whenever you add/change any configurable field/JSON key for rvp pack or ywzj_rvp, to update RVP包新增参数字段说明.md."
---

# YWZJ RVP Param Doc Sync

## 目标

当为 RVP 载具包或附属模组 `ywzj_rvp` 新增/修改任何“可配置字段”（例如 vehicles/weapons JSON 新 key，或新增 key 的默认值/行为）时，必须同步更新项目根目录的文档：

- `E:/ywzj/ywzj_vehicle/RVP包新增参数字段说明.md`

## 何时调用

- 新增或修改任何 `@SerializedName("...")` 字段
- 新增或修改任何会被 `Gson`/json 读取的配置 key
- 新增或修改 RVP 载具包数据结构（`limitless_vehicle/rvp/data/**`）中需要用户填写的字段
- 更改字段默认值、语义、单位、兼容性行为

## 执行规则（必须）

1) 先在代码中确认字段名与默认值
   - 从 Data/Mixin 的 `@SerializedName` 或实际解析逻辑提取字段名
   - 确认默认值与“未填写时行为”

2) 更新文档对应分类
   - vehicles/*.json：放在“载具包参数（vehicles/*.json）”
   - weapons/*.json：放在“载具包参数（weapons/*.json）”
   - 以“武器类型/部件类型”为小节标题，例如：雷达、GPS炸弹、导弹(ARM扩展)

3) 每个字段至少写清楚
   - 字段名
   - 作用
   - 默认值
   - 行为细节（尤其是 >0/==0/不写 的分支）
   - 单位（tick/米/度 等）
   - 兼容性（未安装 ywzj_rvp 或不写字段时是否保持老行为）

4) 给出至少一个最小示例 JSON

## 输出检查

- 文档中出现的字段名必须与代码完全一致（大小写、下划线一致）
- 示例 JSON 必须是可复制粘贴的有效 JSON
