import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { dirname, resolve, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

/** 仅提供回放网页和指定的仿真输出目录；默认只监听本机。 */
const webRoot = dirname(fileURLToPath(import.meta.url));
/** 浏览器模块与 JSON 的响应类型。 */
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8' };

/** 导出工厂便于测试真实 HTTP 导入流程。 */
export function createReplayServer(replayRoot = resolve(webRoot, '../../build/gunner-simulation')) {
  return createServer(async (req, res) => {
    if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405).end(); return; }
    try {
      const path = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
      const isReplay = path.startsWith('/replays/');
      const root = isReplay ? resolve(replayRoot) : webRoot;
      const relative = isReplay ? path.slice('/replays/'.length) : path === '/' ? 'index.html' : path.slice(1);
      const file = resolve(root, relative);
      if (!file.startsWith(root + sep) || relative.includes('\0')) { res.writeHead(403).end(); return; }
      const data = await readFile(file);
      res.writeHead(200, { 'Content-Type': mime[extname(file)] ?? 'application/octet-stream', 'Cache-Control': 'no-store' });
      res.end(req.method === 'HEAD' ? undefined : data);
    } catch (error) {
      const status = error.code === 'ENOENT' || error.code === 'EISDIR' ? 404 : 400;
      res.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8' }).end('文件不可用。请先运行 Gradle gunnerSimulation，或在页面中导入回放 JSON。');
    }
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT ?? 4173);
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error('PORT 必须是 0..65535 的整数');
  const server = createReplayServer(process.env.RVP_REPLAY_DIR);
  server.on('error', error => { console.error(`回放服务启动失败：${error.message}`); process.exitCode = 1; });
  server.listen(port, '127.0.0.1', () => console.log(`Gunner 回放已就绪：http://127.0.0.1:${server.address().port}`));
}
