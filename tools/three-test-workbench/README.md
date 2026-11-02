# Three.js 测试工作台

独立的新工具，参考 `tools/gunner-replay` 的观察方式，通过插件接入不同测试。**新增普通场景只加数据；新增特殊测试只加插件目录，播放、时间轴和相机主体都不用改。**

在项目根目录运行：

```powershell
cd tools/three-test-workbench
npm ci
npm start
```

打开 <http://127.0.0.1:4174>。需要 Node.js 22+；依赖固定为 Three.js 0.180.0，安装后页面完全使用本地文件。按 `Ctrl+C` 停止服务，`$env:PORT='4175'` 可以更换端口。

自带解析抛体示例，可直接使用。要观察项目真实 Gunner 仿真，在项目根目录先执行：

```powershell


./gradlew gunnerSimulation
```

点击“刷新目录”即可发现结果。`RVP_REPLAY_DIR` 可覆盖 Gunner 插件的数据目录；缺少 Gunner 输出时会显示提示，独立示例仍可使用。

RVP 弹体仿真在项目根运行 `./gradlew projectileSimulation`，刷新页面与目录后选择 **RVP 弹体仿真**。武器只读 `limitless_vehicle/rvp/data/rvp/weapons/`。默认六个场景包含炮弹、火箭、炸弹、GPS 导弹、鱼雷入水和冷发射二脉冲。指定其他武器：`./gradlew projectileSimulation "-PprojectileWeapon=rvp:ah64_hydra70"`。

气动和运动直接执行真实实体方法，包含六类弹体、二脉冲、风漂、部署、水中运动、姿态与制导执行器；不使用虚拟段积分器。场景提供理想目标观测与简化环境，不验证实际探测锁定或伤害。回放已升级第 2 版，旧结果需重新计算。[详细范围、初态与对照验证](plugins/projectile/README.md)。

页首新增 **弹体用例生成器 ↗**：以现有 `projectile-simulation` 场景为模板，在三维画面拖动发射点、目标起点与速度端点，分别调整机头与初速方向，按时间轴预览目标的直线/恒加速度运动。支持全部当前场景字段、JSON 导入导出、本机草稿、保存新用例和复制单例仿真命令；真实导弹路线仍由 Java 生成。[编辑器用法与预览边界](plugins/projectile-scenario/README.md)。已经运行旧版服务时请重启 `npm start` 后刷新页面。

- 播放/暂停、逐刻前后、倍速、时间轴跳转、事件跳转；空格和方向键也可操作。
- 选择或点击对象、跟随、俯视、三维视角、已走轨迹、范围与目标连线。
- 插件专属观察面板、原始观察值、测试范围、整场统计与测试进程提供的断言结果。
- 手动导入 JSON 自动识别插件；发生格式识别冲突时可指定“导入格式”。单文件上限 50 MB。

扩展入口：`plugins/<id>/plugin.json`。服务自动扫描该目录，没有需要修改的插件列表。完整的数据格式、插件接口、示例和边界见 [扩展指南](../ThreeJS测试工作台扩展指南.md)。

```powershell
npm run check
npm test
```

测试覆盖格式校验、时间采样、动态新增插件/测试、当前 Java 记录适配与 HTTP 读取边界。`npm run demo` 重新生成解析示例，保留索引中其他测试条目。Java 算法仍由各自的测试任务执行，工作台负责读取结果并回放。
