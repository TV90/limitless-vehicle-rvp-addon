import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as THREE from 'three';
import { PluginRegistry } from '../core/plugins.js';
import { sampleReplay } from '../core/replay.js';
import { createWorkbenchServer } from '../server.mjs';
import projectile from '../plugins/projectile/adapter.js';
import standard from '../plugins/standard/adapter.js';
import { fixture } from './fixture.mjs';

/** 用极小记录检查插件边界；数值正确性由 Java 生产积分回归负责。 */
function record() {
  const data = fixture();
  data.schemaVersion = 2;
  data.format = 'rvp-projectile-simulation'; data.weapon = { id: 'rvp:probe', sha256: '0'.repeat(64), json: {} };
  data.physics = { implementation: 'rvp-entity-flight', entityClass: 'RVP_RocketEntity', motionEntry: 'tickMotion' };
  data.scene = { weapon: 'rvp:probe' }; return data;
}

test('弹体来源与版本需要明确，独立插件不会和通用示例抢文件', async () => {
  const registry = new PluginRegistry(); registry.add(standard, 'standard'); registry.add(projectile, 'projectile');
  const data = record(), before = JSON.stringify(data);
  assert.equal((await registry.decode(data)).plugin.id, 'projectile');
  assert.equal(JSON.stringify(data), before);
  data.weapon.sha256 = 'invalid'; await assert.rejects(registry.decode(data), /来源摘要/);
  data.weapon.sha256 = '0'.repeat(64); data.schemaVersion = 1; await assert.rejects(registry.decode(data), /重新运行/);
  data.schemaVersion = 2; data.scene.weapon = 'rvp:other'; await assert.rejects(registry.decode(data), /来源摘要/);
  data.scene.weapon = 'rvp:probe'; data.physics.implementation = 'rvp-virtual-flight';
  await assert.rejects(registry.decode(data), /真实实体/);
});

test('实际 Java 弹体回放均能读取，时间、坐标、结束事件与初态保持一致', async t => {
  const root = fileURLToPath(new URL('../../../build/projectile-simulation/', import.meta.url));
  let index;
  try { index = JSON.parse(await readFile(resolve(root, 'index.json'), 'utf8')); }
  catch (error) { if (error.code === 'ENOENT') { t.skip('先运行 ./gradlew projectileSimulation'); return; } throw error; }
  const registry = new PluginRegistry(); registry.add(projectile, 'projectile'); registry.add(standard, 'standard');
  assert.ok(index.replays.length > 0);
  for (const entry of index.replays) {
    const data = JSON.parse(await readFile(resolve(root, entry.file), 'utf8'));
    const { compiled } = await registry.decode(data);
    assert.equal(compiled.replay.id, entry.id);
    assert.deepEqual(compiled.frames[0].objects[0].position, data.scene.position);
    assert.equal(compiled.frames.length, compiled.endTick + 1);
    assert.deepEqual(sampleReplay(compiled, compiled.endTick).objects[0].position, data.frames.at(-1).objects[0].position);
    assert.ok(data.events.some(event => event.tick === compiled.endTick));
    assert.ok(data.checks.every(check => check.status !== 'failed'));
    // 在真实 Three.js 中建图形并倒拖；海红旗-10 初帧就燃烧，须按当前帧判断尾焰。
    const visual = registry.renderer('projectile:body')({ THREE, object: data.objects[0] });
    visual.update({ sample: { values: { motorStage: '主发动机燃烧' } } });
    assert.equal(visual.root.children.at(-1).visible, true);
    visual.update({ sample: data.frames[0].objects[0] });
    const initialStage = data.frames[0].objects[0].values.motorStage;
    assert.equal(visual.root.children.at(-1).visible, ['主发动机燃烧', '第二脉冲燃烧'].includes(initialStage));
    visual.update({ sample: { values: { motorStage: '发动机燃尽' } } });
    assert.equal(visual.root.children.at(-1).visible, false);
    assert.ok(projectile.inspect({ sample: data.frames[0].objects[0] }).some(row => row.label.includes('格/秒')));
    visual.root.traverse(node => { node.geometry?.dispose(); node.material?.dispose(); });
  }
});

test('服务自动发现新增弹体插件，通过已生成索引提供记录及模型模块', async t => {
  const server = createWorkbenchServer({ env: {} });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve())));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const catalog = await (await fetch(`${origin}/api/catalog`)).json();
  const source = catalog.plugins.find(plugin => plugin.id === 'projectile');
  assert.ok(source, '没有修改中央插件名单也能发现新增目录');
  for (const path of [source.module, '/plugins/projectile/body.js']) assert.equal((await fetch(origin + path)).status, 200);
  if (!source.cases.length) { assert.ok(source.warnings.some(warning => warning.includes('projectileSimulation'))); return; }
  const data = await (await fetch(origin + source.cases[0].url)).json();
  assert.equal(data.format, 'rvp-projectile-simulation');
  assert.match(data.weapon.sha256, /^[a-f0-9]{64}$/);
});
