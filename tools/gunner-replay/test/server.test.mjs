import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, unlink, rmdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { createReplayServer } from '../server.mjs';

test('本地服务提供页面、模块和指定回放目录，并正确处理缺失记录', async () => {
  const root = await mkdtemp(join(tmpdir(), 'rvp-gunner-test-'));
  const server = createReplayServer(root);
  try {
    await writeFile(join(root, 'index.json'), '{"schemaVersion":1,"replays":[]}');
    server.listen(0, '127.0.0.1'); await once(server, 'listening');
    const url = `http://127.0.0.1:${server.address().port}`;
    const page = await fetch(url); assert.equal(page.status, 200); assert.match(await page.text(), /Gunner · 仿真回放/);
    const module = await fetch(`${url}/replay.js`); assert.match(module.headers.get('content-type'), /javascript/);
    assert.equal((await fetch(`${url}/replays/index.json`)).status, 200);
    assert.equal((await fetch(`${url}/replays/missing.json`)).status, 404);
    assert.equal((await fetch(`${url}/replays/%2e%2e%5cpackage.json`)).status, 403);
    assert.equal((await fetch(url, { method: 'POST' })).status, 405);
  } finally {
    await new Promise(resolve => server.close(resolve));
    // 仅移除本测试写入的一个文件和空目录，不递归处理任何路径。
    await unlink(join(root, 'index.json'));
    await rmdir(root);
  }
});
