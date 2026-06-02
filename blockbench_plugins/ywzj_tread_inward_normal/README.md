## 安装

把整个文件夹 `ywzj_tread_inward_normal` 复制到 Blockbench 插件目录，然后重启 Blockbench。

常见路径（Windows）：
- `%APPDATA%\\Blockbench\\plugins\\`

也可以在 Blockbench 里通过“文件 -> 插件 -> 打开插件目录”进入正确目录。

## 使用

运行后在顶部菜单“工具”中找到：
- `履带：绿色轴指向内侧（YZ）`

默认匹配命名：
- `tread_<字母>_<数字>`，例如：`tread_a_0`

插件会：
- 为每个匹配的 Mesh 创建/复用同名 Group
- 计算 YZ 平面内侧法向，并写入 Group 的 `rotation[0]`
- 将 Mesh 归类到同名 Group 下
- 尽量保持 Mesh 渲染不变（通过世界顶点回写）
