import test from 'node:test';
import assert from 'node:assert/strict';
import { request } from 'node:http';
import { mkdir, mkdtemp, open, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { isAbsolute, join, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createWorkbenchServer } from '../server.mjs';
import { confinedFile, discoverCatalog, resolveInside } from '../lib/catalog.mjs';
import { PluginRegistry } from '../core/plugins.js';
import { MAX_RECORD_BYTES } from '../core/replay.js';
import { fixture } from './fixture.mjs';

/** 所有可写测试数据只放在独立临时目录，清理前核对绝对路径。 */
async function setup(t) {
  const projectRoot = await mkdtemp(join(tmpdir(), 'rvp-workbench-')), pluginRoot = join(projectRoot, 'plugins');
  await mkdir(pluginRoot); await mkdir(join(projectRoot, 'records'));
  await writeFile(join(projectRoot, 'records/one.json'), JSON.stringify(fixture()));
  await writeFile(join(projectRoot, 'records/index.json'), JSON.stringify({ schemaVersion: 1, replays: [{ id: 'one', name: '测试一', file: 'one.json' }] }));
  t.after(async () => {
    const child = relative(resolve(tmpdir()), resolve(projectRoot));
    assert.ok(child.startsWith('rvp-workbench-') && !child.includes('..') && !isAbsolute(child));
    await rm(projectRoot, { recursive: true, force: true });
  });
  return { projectRoot, pluginRoot, env: {} };
}

/** 模拟第三方新增插件目录，服务进程与工作台主体均保持原样。 */
async function addPlugin(options, id, source = {}) {
  const folder = join(options.pluginRoot, id); await mkdir(folder);
  await writeFile(join(folder, 'plugin.json'), JSON.stringify({ schemaVersion: 1, id, name: `插件 ${id}`, module: 'adapter.js',
    sources: [{ id: 'cases', root: 'records', index: 'index.json', ...source }] }));
  await writeFile(join(folder, 'adapter.js'), `export default { apiVersion: 1, id: '${id}', accepts: d => d.format === 'rvp-test-replay', adapt: d => d };`);
  return folder;
}

async function listen(t, options) {
  const server = createWorkbenchServer(options);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve())));
  return `http://127.0.0.1:${server.address().port}`;
}

/** fetch 会标准化 .. 路径；使用原始 HTTP 请求验证服务自身的路径边界。 */
async function rawStatus(url, path) {
  return new Promise((resolve, reject) => {
    const req = request(url, { path }, response => { response.resume(); response.on('end', () => resolve(response.statusCode)); });
    req.on('error', reject); req.end();
  });
}

test('自动目录、静态依赖、实际数据和 HEAD 请求可用；非公开文件与写请求被拒绝', async t => {
  const options = await setup(t); await addPlugin(options, 'alpha'); const url = await listen(t, options);
  const catalog = await (await fetch(`${url}/api/catalog`)).json();
  assert.equal(catalog.plugins[0].cases.length, 1); assert.equal(catalog.plugins[0].module, '/plugins/alpha/adapter.js');
  assert.deepEqual(await (await fetch(url + catalog.plugins[0].cases[0].url)).json(), fixture());
  assert.match(await (await fetch(url)).text(), /三维测试工作台/);
  for (const path of ['/main.js', '/core/replay.js', '/plugins/alpha/adapter.js', '/vendor/three/build/three.module.js', '/vendor/three/build/three.core.js']) {
    const response = await fetch(url + path); assert.equal(response.status, 200, path); assert.match(response.headers.get('content-type'), /javascript/);
  }
  const head = await fetch(`${url}/api/records/alpha/cases/one`, { method: 'HEAD' });
  assert.equal(head.status, 200); assert.equal(await head.text(), '');
  for (const path of ['/server.mjs', '/package.json', '/lib/catalog.mjs', '/plugins/alpha/plugin.json', '/api/records/alpha/cases/absent'])
    assert.equal((await fetch(url + path)).status, 404, path);
  assert.equal((await fetch(`${url}/api/catalog`, { method: 'POST' })).status, 405);
});

test('新增插件与测试条目后刷新即可发现，动态加载插件后能够适配记录', async t => {
  const options = await setup(t), url = await listen(t, options);
  assert.equal((await (await fetch(`${url}/api/catalog`)).json()).plugins.length, 0);
  const folder = await addPlugin(options, 'new-domain');
  let catalog = await (await fetch(`${url}/api/catalog`)).json(); assert.equal(catalog.plugins.length, 1);
  await writeFile(join(options.projectRoot, 'records/two.json'), JSON.stringify({ ...fixture(), id: 'two' }));
  await writeFile(join(options.projectRoot, 'records/index.json'), JSON.stringify({ schemaVersion: 1, replays: [
    { id: 'one', name: '测试一', file: 'one.json' }, { id: 'two', name: '新增测试二', file: 'two.json' },
  ] }));
  catalog = await (await fetch(`${url}/api/catalog`)).json(); assert.equal(catalog.plugins[0].cases.length, 2);
  const module = await import(pathToFileURL(join(folder, 'adapter.js')).href), registry = new PluginRegistry();
  registry.add(module.default, 'new-domain');
  const record = await (await fetch(url + catalog.plugins[0].cases[1].url)).json();
  assert.equal((await registry.decode(record)).compiled.replay.id, 'two');
});

test('坏插件、缺失数据源和坏索引分别给出提示，不影响有效插件', async t => {
  const options = await setup(t); await addPlugin(options, 'valid'); await addPlugin(options, 'missing', { root: 'not-generated' });
  const broken = await addPlugin(options, 'broken'); await writeFile(join(broken, 'plugin.json'), '{bad');
  const unsafe = await addPlugin(options, 'unsafe', { root: '../outside' });
  const catalog = await discoverCatalog(options);
  assert.equal(catalog.plugins.find(plugin => plugin.id === 'valid').cases.length, 1);
  assert.equal(catalog.plugins.find(plugin => plugin.id === 'missing').cases.length, 0);
  assert.equal(catalog.plugins.find(plugin => plugin.id === 'missing').warnings.length, 1);
  assert.match(catalog.warnings.join(''), /broken/);
  assert.match(catalog.plugins.find(plugin => plugin.id === 'unsafe').warnings.join(''), /超出允许目录/);
  assert.ok(unsafe);
  await writeFile(join(options.projectRoot, 'records/index.json'), JSON.stringify({ schemaVersion: 1, replays: [
    { id: 'one', name: '有效', file: 'one.json' }, { id: 'bad', name: '越界', file: '../secret.json' },
  ] }));
  const invalid = await discoverCatalog(options);
  assert.equal(invalid.plugins.find(plugin => plugin.id === 'valid').cases.length, 0); assert.equal(invalid.records.size, 0);
});

test('可通过清单声明的环境变量切换输出目录', async t => {
  const options = await setup(t); await addPlugin(options, 'external', { root: 'missing', rootEnv: 'RVP_CUSTOM_TEST_DIR' });
  options.env.RVP_CUSTOM_TEST_DIR = join(options.projectRoot, 'records');
  const catalog = await discoverCatalog(options); assert.equal(catalog.plugins[0].cases.length, 1);
});

test('拒绝 Windows/URL 路径穿越、空字节和畸形编码', async t => {
  const options = await setup(t); await addPlugin(options, 'alpha'); const url = await listen(t, options);
  for (const path of ['/core/../server.mjs', '/core/%2e%2e/server.mjs', '/plugins/alpha/%2e%2e%5cserver.mjs', '/api/records/%00'])
    assert.equal(await rawStatus(url, path), 403, path);
  assert.equal(await rawStatus(url, '/core/%zz.js'), 400);
  for (const path of ['../secret', '..\\secret', 'C:\\secret', '/secret', 'safe/../../secret'])
    assert.throws(() => resolveInside(options.projectRoot, path), /超出允许目录/);
});

test('实际磁盘路径也被约束，目录链接不能读取数据源外的文件', async t => {
  const options = await setup(t), outside = join(options.projectRoot, 'outside'); await mkdir(outside);
  await writeFile(join(outside, 'secret.json'), '{}');
  try { await symlink(outside, join(options.projectRoot, 'records/escape'), process.platform === 'win32' ? 'junction' : 'dir'); }
  catch (error) { if (['EPERM', 'ENOSYS'].includes(error.code)) { t.skip('当前系统不允许创建目录链接'); return; } throw error; }
  await assert.rejects(confinedFile(join(options.projectRoot, 'records'), 'escape/secret.json'), /超出允许目录/);
});

test('超大记录在读取正文前返回413，缺失记录返回404', async t => {
  const options = await setup(t); await addPlugin(options, 'alpha'); const url = await listen(t, options);
  const file = await open(join(options.projectRoot, 'records/one.json'), 'w');
  try { await file.truncate(MAX_RECORD_BYTES + 1); } finally { await file.close(); }
  assert.equal((await fetch(`${url}/api/records/alpha/cases/one`)).status, 413);
  await writeFile(join(options.projectRoot, 'records/index.json'), JSON.stringify({ schemaVersion: 1, replays: [{ id: 'missing', name: '缺文件', file: 'missing.json' }] }));
  assert.equal((await fetch(`${url}/api/records/alpha/cases/missing`)).status, 404);
});
