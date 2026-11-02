import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as THREE from 'three';
import { PluginRegistry } from '../core/plugins.js';
import gunner from '../plugins/gunner/adapter.js';
import standard from '../plugins/standard/adapter.js';
import { fixture } from './fixture.mjs';

test('插件必须同名、满足接口版本且不能重名或注册坏绘制器', () => {
  const registry = new PluginRegistry(); registry.add(standard, 'standard');
  assert.throws(() => registry.add(standard, 'standard'), /重复/);
  assert.throws(() => registry.add(standard, 'other'), /接口无效/);
  assert.throws(() => registry.add({ ...standard, apiVersion: 2 }, 'standard'), /接口无效/);
  assert.throws(() => registry.add({ ...standard, id: 'broken', renderers: { wrong: 0 } }, 'broken'), /绘制器/);
});

test('新增独立插件即可接入另一数据格式与绘制器，不修改主体', async () => {
  const registry = new PluginRegistry(); registry.add(standard, 'standard');
  const renderer = () => ({ root: new THREE.Group() });
  const extension = { apiVersion: 1, id: 'extension', accepts: data => data.format === 'independent-test',
    adapt: data => ({ ...data, format: 'rvp-test-replay' }), renderers: { probe: renderer } };
  registry.add(extension, 'extension');
  assert.equal(registry.renderer('extension:constructor'), undefined);
  const data = fixture(); data.format = 'independent-test'; data.objects[0].renderer = 'extension:probe';
  const result = await registry.decode(data);
  assert.equal(result.plugin.id, 'extension'); assert.equal(result.compiled.endTick, 10);
  assert.equal(registry.renderer('extension:probe'), renderer); assert.equal(registry.renderer('unknown:probe'), undefined);
});

test('识别冲突要求指定插件；未知文件和适配后的坏记录被拒绝', async () => {
  const registry = new PluginRegistry(); registry.add(standard, 'standard');
  registry.add({ ...standard, id: 'second' }, 'second');
  await assert.rejects(registry.decode(fixture()), /多个插件/);
  assert.equal((await registry.decode(fixture(), 'second')).plugin.id, 'second');
  await assert.rejects(registry.decode({ format: 'unknown' }), /没有已加载/);
  const data = fixture(); data.groundY = Infinity;
  await assert.rejects(registry.decode(data, 'standard'), /groundY/);
});

test('Gunner 当前 Java 输出的所有实际场景都可独立接入新工具', async t => {
  const root = fileURLToPath(new URL('../../../build/gunner-simulation/', import.meta.url));
  let index;
  try { index = JSON.parse(await readFile(resolve(root, 'index.json'), 'utf8')); }
  catch (error) { if (error.code === 'ENOENT') { t.skip('运行 ./gradlew gunnerSimulation 后验证实际 Java 记录'); return; } throw error; }
  const registry = new PluginRegistry(); registry.add(gunner, 'gunner'); registry.add(standard, 'standard');
  assert.ok(index.replays.length > 0);
  for (const entry of index.replays) {
    const data = JSON.parse(await readFile(resolve(root, entry.file), 'utf8')), before = JSON.stringify(data);
    const { plugin, compiled } = await registry.decode(data);
    assert.equal(plugin.id, 'gunner'); assert.equal(compiled.replay.id, entry.id); assert.equal(compiled.endTick, data.scene.ticks);
    assert.equal(compiled.replay.events.length, data.frames.reduce((sum, frame) => sum + frame.events.length, 0));
    assert.deepEqual(compiled.frames.at(-1).objects[0].position, data.frames.at(-1).actors[0].position);
    assert.equal(compiled.replay.checks.length, 0); assert.equal(JSON.stringify(data), before);
    // 用实际 Three.js 创建专用模型并更新，保证插件不依赖旧工具或 DOM。
    const object = compiled.replay.objects[0], sample = compiled.frames[0].objects[0];
    const visual = registry.renderer(object.renderer)({ THREE, object });
    assert.equal(visual.root.isObject3D, true); visual.update({ sample });
    assert.ok(plugin.inspect({ object, sample, replay: compiled.replay }).every(row => typeof row.label === 'string'));
    visual.root.traverse(node => { node.geometry?.dispose(); node.material?.dispose(); });
  }
});
