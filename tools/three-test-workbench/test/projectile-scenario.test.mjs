import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, readdir, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { isAbsolute, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as THREE from 'three';
import { createWorkbenchServer } from '../server.mjs';
import { PluginRegistry } from '../core/plugins.js';
import { sampleReplay } from '../core/replay.js';
import projectile from '../plugins/projectile/adapter.js';
import scenarioPlugin from '../plugins/projectile-scenario/adapter.js';
import { DEFAULT_SCENARIO, anglesFromVelocity, buildPreviewReplay, insertTargetPoint, normalizeScenario, simulationCommand, targetAt, validWeaponId, velocityFromAngles } from '../plugins/projectile-scenario/scenario.js';

/** 所有写入回归使用独立临时项目；真实仓库的场景和权威武器始终只读。 */
async function setup(t) {
  const projectRoot = await mkdtemp(join(tmpdir(), 'rvp-scenario-editor-'));
  const scenarioRoot = join(projectRoot, 'src/test/resources/projectile-simulation');
  const weaponRoot = join(projectRoot, 'limitless_vehicle/rvp/data/rvp/weapons');
  await mkdir(scenarioRoot, { recursive: true }); await mkdir(weaponRoot, { recursive: true });
  await writeFile(join(weaponRoot, 'probe.json'), JSON.stringify({ type: 'rvp:missile', name: '测试弹', projectile_data: { inherit_vehicle_velocity: true } }));
  await writeFile(join(weaponRoot, 'pod.json'), JSON.stringify({ type: 'rvp:pod', name: '不产生弹体' }));
  await mkdir(join(weaponRoot, '_backup'));
  await writeFile(join(weaponRoot, '_backup/old.json'), JSON.stringify({ type: 'rvp:missile' }));
  await writeFile(join(scenarioRoot, 'template.json'), JSON.stringify({ ...DEFAULT_SCENARIO, id: 'original', weapon: 'rvp:probe' }));
  const server = createWorkbenchServer({ projectRoot, env: {} });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
    const child = relative(resolve(tmpdir()), resolve(projectRoot));
    assert.ok(child.startsWith('rvp-scenario-editor-') && !child.includes('..') && !isAbsolute(child));
    await rm(projectRoot, { recursive: true, force: true });
  });
  return { origin: `http://127.0.0.1:${server.address().port}`, scenarioRoot, weaponRoot };
}

/** 基础场景采用当前 schema；具体测试只变更其关心的输入。 */
const scene = overrides => normalizeScenario({ ...structuredClone(DEFAULT_SCENARIO), weapon: 'rvp:probe', ...overrides });
/** 比较角度变换的浮点误差，不要求三角函数逐位相同。 */
function closeVector(actual, expected) { actual.forEach((v, i) => assert.ok(Math.abs(v - expected[i]) < 1e-9, `${actual} != ${expected}`)); }

test('方向遵循 Minecraft 正负号，机头和初速可以分别设置，零初速不猜角度', () => {
  closeVector(velocityFromAngles(0, 0, 60), [0, 0, 60]);
  closeVector(velocityFromAngles(90, 0, 60), [-60, 0, 0]);
  closeVector(velocityFromAngles(-90, 0, 60), [60, 0, 0]);
  closeVector(velocityFromAngles(0, 90, 60), [0, 60, 0]);
  closeVector(velocityFromAngles(180, -30, 60), [0, -30, -Math.sqrt(2700)]);
  for (const v of [[30, -40, 50], [-120, 10, -45], [0, -20, 0], [0, 0, 0]]) {
    const angles = anglesFromVelocity(v); closeVector(velocityFromAngles(angles.yaw, angles.pitch, angles.speed), v);
  }
  const input = scene({ yaw: 90, initialVelocity: [0, 0, 60] }), replay = buildPreviewReplay(input);
  assert.equal(replay.frames[0].objects.find(o => o.id === 'launch').yaw, 90);
  assert.equal(replay.frames[0].objects.find(o => o.id === 'velocity').yaw, 0);
});

test('目标解析式按秒换算，与恒加速度运动一致且倒拖不受已播放历史影响', () => {
  const input = scene({ target: [100, 200, 300], targetVelocity: [5, -2, 10], targetAcceleration: [2, 4, -6] });
  assert.deepEqual(targetAt(input, 40), { position: [114, 204, 308], velocity: [9, 6, -2] });
  targetAt(input, 600);
  assert.deepEqual(targetAt(input, 0), { position: [100, 200, 300], velocity: [5, -2, 10] });
  assert.equal(targetAt(scene(), 50), null);
});

test('自由轨迹在中途转弯、停留和末点后静止，折点取下一段速度且支持倒拖', () => {
  const input = scene({ ticks: 80, target: [100, 200, 300], targetVelocity: [999, 999, 999], targetAcceleration: [10, 10, 10],
    targetPath: [{ tick: 20, position: [120, 200, 300] }, { tick: 40, position: [120, 220, 280] }, { tick: 60, position: [120, 220, 280] }] });
  assert.deepEqual(targetAt(input, 10), { position: [110, 200, 300], velocity: [20, 0, 0] });
  assert.deepEqual(targetAt(input, 20), { position: [120, 200, 300], velocity: [0, 20, -20] });
  assert.deepEqual(targetAt(input, 30), { position: [120, 210, 290], velocity: [0, 20, -20] });
  assert.deepEqual(targetAt(input, 30.5).position, [120, 210.5, 289.5]);
  for (const tick of [40, 60, 80]) assert.deepEqual(targetAt(input, tick), { position: [120, 220, 280], velocity: [0, 0, 0] });
  assert.deepEqual(targetAt(input, 0), { position: [100, 200, 300], velocity: [20, 0, 0] });
  const replay = buildPreviewReplay(input);
  assert.deepEqual(replay.frames[30].objects.find(o => o.id === 'target').position, [120, 208, 290]);
});

test('任意时刻插点保留原点和终点，排序后可无损导出；拒绝重复、越界、缺失坐标和溢出', () => {
  const input = scene({ ticks: 80, target: [100, 200, 300], targetVelocity: [10, 0, 0] }), before = structuredClone(input);
  const first = insertTargetPoint(input, 30);
  assert.deepEqual(first.targetPath, [{ tick: 30, position: [115, 200, 300] }, { tick: 80, position: [140, 200, 300] }]);
  assert.deepEqual(input, before);
  const edited = insertTargetPoint(first, 10, [95, 220, 310]);
  assert.deepEqual(edited.targetPath.map(p => p.tick), [10, 30, 80]);
  assert.deepEqual(normalizeScenario(JSON.parse(JSON.stringify(edited))), edited);
  for (const tick of [0, 30, 81, NaN, 1.5]) assert.throws(() => insertTargetPoint(first, tick));
  const points = [{ tick: 20, position: [1, 2, 3] }];
  for (const targetPath of [null, {}, [null], [{ tick: 0, position: [1, 2, 3] }], [{ tick: 1.5, position: [1, 2, 3] }],
    [...points, ...points], [{ tick: 601, position: [1, 2, 3] }], [{ tick: 20, position: [1, 2] }],
    [{ tick: 20, position: [1, Infinity, 3] }], [{ ...points[0], old_velocity: [1, 2, 3] }],
    Array.from({ length: 257 }, (_, i) => ({ tick: i + 1, position: [1, 2, 3] }))]) assert.throws(() => scene({ target: [0, 164, 500], targetPath }));
  assert.throws(() => scene({ targetPath: points }), /启用目标/);
  assert.throws(() => scene({ target: [-1e308, 164, 500], targetPath: [{ tick: 20, position: [1e308, 164, 500] }] }), /有限数值/);
  assert.equal(scene({ target: [0, 164, 500], targetPath: Array.from({ length: 256 }, (_, i) => ({ tick: i + 1, position: [i, 164, 500] })) }).targetPath.length, 256);
});

test('当前 Java 数据字段完整对应，全部已有场景可无损填默认值后导出', async () => {
  const java = await readFile(fileURLToPath(new URL('../../../src/test/java/org/ywzj/rvp/projectile/simulation/RVP_ProjectileSimulationScenario.java', import.meta.url)), 'utf8');
  const keys = [...java.matchAll(/public (?:int|float|long|boolean|double(?:\[\])?|Double|String|TargetPoint\[\]) (\w+)\s*[=;]/g)].map(match => match[1]);
  assert.deepEqual(Object.keys(DEFAULT_SCENARIO).sort(), keys.sort(), 'Java 增加输入字段时编辑器也须显式支持');
  const root = fileURLToPath(new URL('../../../src/test/resources/projectile-simulation/', import.meta.url));
  const files = (await readdir(root)).filter(file => file.endsWith('.json'));
  // 验证仓库实际提交的六类基线，再遍历所有附加场景；不依赖开发机未提交的 200 份输入。
  for (const file of ['01_cannon.json', '02_rocket.json', '03_bomb.json', '04_GPS_missile.json',
    '05_torpedo.json', '06_cold_launch.json']) assert.ok(files.includes(file), `缺少基线场景 ${file}`);
  for (const file of files) {
    const input = JSON.parse((await readFile(join(root, file), 'utf8')).replace(/^\uFEFF/, '')), before = JSON.stringify(input);
    const normalized = normalizeScenario(input);
    for (const [key, value] of Object.entries(input)) assert.deepEqual(normalized[key], value, `${file}: ${key}`);
    assert.deepEqual(normalizeScenario(JSON.parse(JSON.stringify(normalized))), normalized);
    assert.equal(JSON.stringify(input), before);
  }
});

test('拒绝坏版本、未知键、非有限三元组、角度/水面/时长边界和会丢精度的种子', () => {
  for (const input of [{ schemaVersion: 0 }, { ticks: 0 }, { ticks: 20001 }, { ticks: 1.5 }, { coldLaunchTicks: -1 },
    { coldLaunchTicks: 20001 }, { pitch: 91 }, { yaw: Infinity }, { position: [0, 64, 0] }, { waterY: 64 },
    { initialVelocity: [0, NaN, 0] }, { target: [0, 1] }, { targetRadius: 0 }, { seed: Number.MAX_SAFE_INTEGER + 1 },
    { guidance: 'true' }, { name: ' ' }, { old_velocity: [0, 0, 1] }]) assert.throws(() => scene(input), undefined, JSON.stringify(input));
  for (const id of ['rvp:../secret', 'rvp:_backup/probe', 'rvp:a//b', 'rvp:/probe', 'base:probe', 'rvp:a/./b', 'rvp:a\\b']) {
    assert.equal(validWeaponId(id), false, id); assert.throws(() => scene({ weapon: id }));
  }
  assert.equal(scene({ ticks: 20000, coldLaunchTicks: 20000, pitch: -90 }).ticks, 20000);
});

test('场景插件独立识别，几何预览可拖到首尾且不伪造导弹飞行或命中结果', async () => {
  const registry = new PluginRegistry(); registry.add(projectile, 'projectile'); registry.add(scenarioPlugin, 'projectile-scenario');
  const input = scene({ ticks: 40, target: [100, 200, 300], targetVelocity: [5, -2, 10], targetAcceleration: [2, 4, -6] });
  const { compiled, plugin } = await registry.decode(input); assert.equal(plugin.id, 'projectile-scenario');
  assert.equal(compiled.endTick, 40);
  assert.deepEqual(sampleReplay(compiled, 40).objects.find(o => o.id === 'target').position, [114, 202, 308]);
  assert.deepEqual(sampleReplay(compiled, 0).objects.find(o => o.id === 'target').position, [100, 198, 300]);
  assert.deepEqual(compiled.frames.at(-1).objects[0].position, input.position);
  assert.ok(compiled.replay.checks.every(check => check.status === 'info'));
  assert.equal(scenarioPlugin.accepts({ format: 'rvp-projectile-simulation', schemaVersion: 2, scene: input }), false);
  const visual = registry.renderer('projectile-scenario:arrow')({ THREE, object: compiled.replay.objects[0] });
  assert.ok(visual.root.isObject3D); visual.root.traverse(node => { node.geometry?.dispose(); node.material?.dispose(); });
  const noTarget = buildPreviewReplay(scene({ ticks: 1, guidance: false })); assert.equal(noTarget.objects.length, 1);
});

test('与真实 Java 回放的目标观测逐刻比较，预览目标没有另写一套运动规则', async t => {
  const root = fileURLToPath(new URL('../../../build/projectile-simulation/', import.meta.url));
  let index;
  try { index = JSON.parse(await readFile(join(root, 'index.json'), 'utf8')); }
  catch (error) { if (error.code === 'ENOENT') { t.skip('先运行 projectileSimulation 以验证真实目标观测'); return; } throw error; }
  let count = 0, pathCount = 0;
  for (const entry of index.replays) {
    const replay = JSON.parse(await readFile(join(root, entry.file), 'utf8'));
    if (!replay.scene.target || !(replay.scene.targetAcceleration.some(v => v) || replay.scene.targetPath?.length)) continue;
    const input = normalizeScenario(replay.scene);
    for (const frame of replay.frames) {
      const target = frame.objects.find(object => object.id === 'target');
      // Java 记录采用球底坐标，而输入与目标脚本采用球心坐标。
      assert.ok(target); closeVector(targetAt(input, frame.tick).position, target.position.map((value, axis) => axis === 1 ? value + input.targetRadius : value)); count++;
      if (input.targetPath.length) pathCount++;
    }
  }
  assert.ok(count > 0, '必须实际核对加速目标');
  assert.ok(pathCount > 60, '必须实际核对转弯和停留路线，不能只测首帧');
});

test('新工具通过清单自动发现，静态工具页可打开，服务端模块保持不公开', async t => {
  const { origin } = await setup(t);
  const catalog = await (await fetch(`${origin}/api/catalog`)).json();
  const plugin = catalog.plugins.find(plugin => plugin.id === 'projectile-scenario');
  assert.equal(plugin.tools[0].url, '/plugins/projectile-scenario/index.html');
  for (const file of ['index.html', 'editor.css', 'editor.js', 'view.js', 'scenario.js']) assert.equal((await fetch(`${origin}/plugins/projectile-scenario/${file}`)).status, 200, file);
  assert.equal((await fetch(`${origin}/plugins/projectile-scenario/server.mjs`)).status, 404);
  assert.equal((await fetch(`${origin}/api/plugins/projectile/catalog`)).status, 404);
  assert.equal((await fetch(`${origin}/api/plugins/projectile-scenario/catalog`, { method: 'HEAD' })).status, 200);
  const data = await (await fetch(`${origin}/api/plugins/projectile-scenario/catalog`)).json();
  assert.equal(data.scenarios.length, 1); assert.equal(data.weapons.length, 1);
  assert.equal(data.weapons[0].inheritVehicleVelocity, true, '读取 projectile_data 下的实际继承开关');
  assert.equal(data.weapons[0].id, 'rvp:probe');
});

test('保存只创建新场景，重复 ID/同名坏文件不会覆盖；原文件及权威武器保持原样', async t => {
  const { origin, scenarioRoot, weaponRoot } = await setup(t), url = `${origin}/api/plugins/projectile-scenario/scenarios`;
  const before = await readFile(join(scenarioRoot, 'template.json')), weaponBefore = await readFile(join(weaponRoot, 'probe.json'));
  const save = input => fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(input) });
  const input = scene({ id: 'new_case', initialVelocity: [20, 10, -30], target: [0, 164, 500], targetAcceleration: [3, 0, 0],
    targetPath: [{ tick: 40, position: [40, 180, 500] }, { tick: 80, position: [40, 180, 450] }] });
  assert.equal((await save(input)).status, 201);
  assert.deepEqual(JSON.parse(await readFile(join(scenarioRoot, 'new_case.json'), 'utf8')), input);
  assert.equal((await save({ ...input, pitch: 15 })).status, 409);
  assert.equal((await save({ ...input, id: 'original' })).status, 409, '不同文件名里的相同 id 也被拒绝');
  await writeFile(join(scenarioRoot, 'bad.json'), 'not json');
  assert.equal((await save({ ...input, id: 'bad' })).status, 409);
  assert.equal(await readFile(join(scenarioRoot, 'bad.json'), 'utf8'), 'not json');
  assert.deepEqual(await readFile(join(scenarioRoot, 'template.json')), before);
  assert.deepEqual(await readFile(join(weaponRoot, 'probe.json')), weaponBefore);
});

test('保存拒绝外来页面、路径输入、非 JSON、超大正文、非法武器和目录外链接', async t => {
  const { origin, scenarioRoot, weaponRoot } = await setup(t), url = `${origin}/api/plugins/projectile-scenario/scenarios`;
  const save = (input, headers = {}) => fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(input) });
  assert.equal((await save(scene(), { Origin: 'https://outside.example' })).status, 403);
  assert.equal((await save(scene(), { 'Content-Type': 'text/plain' })).status, 415);
  assert.equal((await save({ ...scene(), id: '../escape' })).status, 400);
  assert.equal((await save({ ...scene(), weapon: 'rvp:_backup/old' })).status, 400);
  assert.equal((await save({ ...scene(), weapon: 'rvp:pod' })).status, 400);
  assert.equal((await save({ ...scene(), description: 'x'.repeat(128 * 1024) })).status, 413);
  assert.equal((await fetch(url, { method: 'DELETE' })).status, 405);
  const outside = join(scenarioRoot, 'outside.json'); await writeFile(outside, JSON.stringify({ type: 'rvp:missile' }));
  try { await symlink(outside, join(weaponRoot, 'escape.json')); }
  catch (error) { if (['EPERM', 'ENOSYS'].includes(error.code)) return; throw error; }
  assert.equal((await save({ ...scene(), weapon: 'rvp:escape' })).status, 403);
});

test('单例命令使用安全文件 ID 和独立输出目录，不允许把 shell 内容当场景路径', () => {
  const command = simulationCommand(scene());
  assert.match(command, /^\.\/gradlew projectileSimulation /); assert.match(command, /projectile-simulation\/custom_projectile\.json/);
  assert.match(command, /-PprojectileOutput=build\/projectile-simulation-editor/);
  assert.throws(() => simulationCommand({ id: 'bad`$(x)' }));
});
