import { createServer } from 'node:http';
import { dirname, extname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { confinedFile, discoverCatalog, FileError, readJSON, readLimited } from './lib/catalog.mjs';
import { isId } from './core/replay.js';

/** 新工具独立提供页面、插件和 Three.js，不依赖旧工具的文件或 node_modules。 */
const webRoot = dirname(fileURLToPath(import.meta.url));
/** 只向浏览器开放需要的静态类型。 */
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.png': 'image/png', '.jpg': 'image/jpeg', '.svg': 'image/svg+xml' };

/** 工厂参数只指定通用目录，业务数据源全部来自插件清单。 */
export function createWorkbenchServer({ projectRoot = resolve(webRoot, '../..'), pluginRoot = resolve(webRoot, 'plugins'), env = process.env } = {}) {
  return createServer(async (request, response) => {
    const send = (status, type, body) => {
      response.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
      response.end(request.method === 'HEAD' ? undefined : body);
    };
    try {
      // 不先用 URL 标准化路径，保留 .. 原文用于拒绝穿越目录的请求。
      const path = decodeURIComponent(request.url.split('?')[0]);
      if (!path.startsWith('/') || path.includes('\\') || path.includes('\0') || path.split('/').includes('..')) throw new FileError(403, '路径超出允许目录');
      if (path.startsWith('/api/plugins/')) {
        const [id, ...parts] = path.slice('/api/plugins/'.length).split('/');
        if (!isId(id)) throw new FileError(404, '没有此插件接口');
        // 仅执行本地清单主动声明的服务模块；浏览器输入不能指定模块路径。
        const manifest = await readJSON(await confinedFile(pluginRoot, `${id}/plugin.json`), 1024 * 1024);
        if (manifest.schemaVersion !== 1 || manifest.id !== id || typeof manifest.server !== 'string'
          || !manifest.server.endsWith('.mjs')) throw new FileError(404, '插件未提供服务接口');
        if (!['GET', 'HEAD'].includes(request.method) && request.headers.origin
          && request.headers.origin !== `http://${request.headers.host}`) throw new FileError(403, '仅允许工作台页面保存数据');
        const module = await import(pathToFileURL(await confinedFile(resolve(pluginRoot, id), manifest.server)).href);
        if (typeof module.handleRequest !== 'function') throw new FileError(500, '插件服务入口无效');
        await module.handleRequest({ request, path: `/${parts.join('/')}`, projectRoot, send }); return;
      }
      if (!['GET', 'HEAD'].includes(request.method)) { response.setHeader('Allow', 'GET, HEAD'); send(405, 'text/plain', '仅支持读取'); return; }
      if (path === '/api/catalog' || path.startsWith('/api/records/')) {
        // 每次刷新重新发现；新增插件/索引条目后无需修改主体或重启服务。
        const catalog = await discoverCatalog({ projectRoot, pluginRoot, env });
        if (path === '/api/catalog') {
          send(200, 'application/json; charset=utf-8', JSON.stringify({ plugins: catalog.plugins, warnings: catalog.warnings })); return;
        }
        const record = catalog.records.get(path);
        if (!record) throw new FileError(404, '没有此测试记录，请刷新目录');
        send(200, 'application/json; charset=utf-8', await readLimited(await confinedFile(record.root, record.file))); return;
      }
      let root = webRoot, file;
      if (path.startsWith('/plugins/')) {
        root = pluginRoot; file = path.slice('/plugins/'.length);
        if (!['.html', '.css', '.js', '.png', '.jpg', '.svg'].includes(extname(file))) throw new FileError(404, '未公开此插件文件');
      } else if (path.startsWith('/vendor/three/')) {
        root = resolve(webRoot, 'node_modules/three'); file = path.slice('/vendor/three/'.length);
        if (!file.endsWith('.js') || (!file.startsWith('build/') && !file.startsWith('examples/jsm/'))) throw new FileError(404, '未公开此依赖文件');
      } else {
        file = path === '/' ? 'index.html' : path.slice(1);
        if (!['index.html', 'style.css', 'main.js'].includes(file) && !/^core\/[a-z-]+\.js$/.test(file)) throw new FileError(404, '未公开此文件');
      }
      send(200, mime[extname(file)] ?? 'application/octet-stream', await readLimited(await confinedFile(root, file)));
    } catch (error) {
      const status = error.status ?? (['ENOENT', 'ENOTDIR', 'EISDIR'].includes(error.code) ? 404 : 400);
      send(status, 'text/plain; charset=utf-8', error.message);
    }
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT ?? 4174);
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error('PORT 必须是 0..65535 的整数');
  const server = createWorkbenchServer();
  server.on('error', error => { console.error(`工作台启动失败：${error.message}`); process.exitCode = 1; });
  server.listen(port, '127.0.0.1', () => console.log(`Three.js 测试工作台：http://127.0.0.1:${server.address().port}`));
}
