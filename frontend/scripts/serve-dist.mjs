#!/usr/bin/env node
/**
 * 生产静态资源服务（零依赖，仅使用 Node 内置模块）。
 *
 * 职责：
 *   1. 托管 frontend/dist 下的构建产物；
 *   2. 把 /api/* 同源反向代理到后端 Spring Boot（默认 http://127.0.0.1:8080），
 *      使生产环境浏览器端 axios 走同源请求（VITE_API_BASE_URL 为空），
 *      同时保留 cpolar 隧道的 Origin/请求体以便后端 CORS 与鉴权正常工作；
 *   3. 提供静态资源缓存策略与文本资源 gzip，避免引入 express/压缩中间件等新依赖。
 *
 * 用法（在 frontend 目录下执行，需先 npm run build 生成 dist）：
 *   npm run serve
 *   或 node scripts/serve-dist.mjs
 *
 * 可选环境变量（均有默认值，通常无需设置）：
 *   HOST=0.0.0.0 PORT=5173 API_TARGET=http://127.0.0.1:8080
 */
import http from 'node:http'
import { createReadStream } from 'node:fs'
import { promises as fs } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { gzip as zlibGzip } from 'node:zlib'
import { promisify } from 'node:util'

const gzipAsync = promisify(zlibGzip)

/** 脚本所在目录（frontend/scripts） */
const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url))
/** 静态资源根目录：frontend/dist */
const DIST_ROOT = path.resolve(SCRIPT_DIR, '..', 'dist')
/** SPA 回退入口文件 */
const INDEX_FILE = path.join(DIST_ROOT, 'index.html')

/** 监听地址与端口：默认 0.0.0.0:5173（与前端开发端口一致，可用 PORT 覆盖） */
const HOST = process.env.HOST || '0.0.0.0'
const PORT = Number.parseInt(process.env.PORT || '5173', 10)
/** 后端地址：/api/* 的代理目标 */
const API_TARGET = new URL(process.env.API_TARGET || 'http://127.0.0.1:8080')
/** 转发给后端时固定使用的 Host 头（后端按本机地址处理，勿透传隧道域名） */
const API_HOST_HEADER = API_TARGET.host

/** 反向代理超时时间（毫秒），超时按上游失败处理 */
const PROXY_TIMEOUT_MS = 30_000

/** 常见静态资源扩展名 -> MIME 类型 */
const MIME_TYPES = new Map([
  ['.html', 'text/html'],
  ['.htm', 'text/html'],
  ['.js', 'text/javascript'],
  ['.mjs', 'text/javascript'],
  ['.css', 'text/css'],
  ['.json', 'application/json'],
  ['.map', 'application/json'],
  ['.txt', 'text/plain'],
  ['.md', 'text/markdown'],
  ['.xml', 'application/xml'],
  ['.svg', 'image/svg+xml'],
  ['.png', 'image/png'],
  ['.jpg', 'image/jpeg'],
  ['.jpeg', 'image/jpeg'],
  ['.gif', 'image/gif'],
  ['.webp', 'image/webp'],
  ['.avif', 'image/avif'],
  ['.ico', 'image/x-icon'],
  ['.woff', 'font/woff'],
  ['.woff2', 'font/woff2'],
  ['.ttf', 'font/ttf'],
  ['.otf', 'font/otf'],
  ['.eot', 'application/vnd.ms-fontobject'],
  ['.wasm', 'application/wasm'],
  ['.webmanifest', 'application/manifest+json'],
  ['.pdf', 'application/pdf']
])

/** 需要追加 charset=utf-8 的文本类型 */
const TEXT_MIME_TYPES = new Set([
  'text/html',
  'text/css',
  'text/javascript',
  'application/json',
  'application/xml',
  'image/svg+xml',
  'text/plain',
  'text/markdown',
  'application/manifest+json'
])

/** 可进行 gzip 压缩的 MIME 类型（文本类资源） */
const GZIP_MIME_TYPES = new Set([
  ...TEXT_MIME_TYPES,
  'application/javascript',
  'text/xml',
  'application/xhtml+xml'
])

/** 逐跳首部：反向代理时不透传（由 Node 自行管理连接语义） */
const HOP_BY_HOP_HEADERS = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade'
])

/**
 * gzip 结果缓存：key 为「绝对路径 + mtime + size + encodings」。
 * 命中时避免对同一构建产物重复压缩；dist 产物是带 hash 的，缓存基本长期有效。
 */
const gzipCache = new Map()
const GZIP_CACHE_LIMIT = 128

/** 根据扩展名取 MIME 类型；未知类型退回二进制流。 */
function contentTypeFor(filePath) {
  const ext = path.extname(filePath).toLowerCase()
  return MIME_TYPES.get(ext) || 'application/octet-stream'
}

/** 文本类型追加 charset，保证中文内容不出现乱码。 */
function withCharset(mime) {
  return TEXT_MIME_TYPES.has(mime) ? mime + '; charset=utf-8' : mime
}

/** 判断客户端是否接受 gzip（Accept-Encoding 含 gzip 即视为支持）。 */
function acceptsGzip(req) {
  const header = req.headers['accept-encoding']
  return typeof header === 'string' && header.toLowerCase().includes('gzip')
}

/** 依据 URL 路径决定 Cache-Control：带 hash 的 /assets/* 长期 immutable，其余 no-cache。 */
function cacheControlFor(pathname) {
  if (pathname.startsWith('/assets/')) {
    return 'public, max-age=31536000, immutable'
  }
  return 'no-cache'
}

/** 发送纯文本错误响应，并统一禁用缓存。 */
function sendText(res, statusCode, message, extraHeaders = {}) {
  // 客户端可能已断开，避免对已结束的响应重复写入
  if (res.writableEnded || res.destroyed) {
    return
  }
  const body = message + '\n'
  res.writeHead(statusCode, {
    'Content-Type': 'text/plain; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    ...extraHeaders
  })
  res.end(body)
}

/** 发送 JSON 响应（用于代理失败等结构化错误）。 */
function sendJson(res, statusCode, payload, extraHeaders = {}) {
  if (res.writableEnded || res.destroyed) {
    return
  }
  const body = JSON.stringify(payload)
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    ...extraHeaders
  })
  res.end(body)
}

/**
 * 解析请求 URL 为安全解码后的 pathname。
 * 解码失败或包含空字节时返回 null（交给调用方回 400）。
 */
function decodePathname(rawUrl) {
  let pathname
  try {
    pathname = new URL(rawUrl, 'http://placeholder.local').pathname
  } catch {
    return null
  }
  let decoded
  try {
    decoded = decodeURIComponent(pathname)
  } catch {
    return null
  }
  if (decoded.includes('\u0000')) {
    return null
  }
  return decoded
}

/**
 * 把 URL pathname 映射到 dist 内的绝对文件路径，防止目录穿越。
 * 返回 null 表示路径非法（含 .. 片段或解析后越出 dist 根目录）。
 */
function toSafeFilePath(pathname) {
  const segments = pathname.split(/[\\/]+/)
  if (segments.some((segment) => segment === '..')) {
    return null
  }
  const relativePath = pathname.replace(/^[/\\]+/, '')
  const candidate = path.resolve(DIST_ROOT, relativePath)
  const rootPrefix = DIST_ROOT.endsWith(path.sep) ? DIST_ROOT : DIST_ROOT + path.sep
  if (candidate !== DIST_ROOT && !candidate.startsWith(rootPrefix)) {
    return null
  }
  return candidate
}

/** 读取并缓存指定文件的 gzip 内容。 */
async function gzipFile(filePath, stats) {
  const key = filePath + '|' + stats.mtimeMs + '|' + stats.size
  const cached = gzipCache.get(key)
  if (cached) {
    return cached
  }
  const buffer = await fs.readFile(filePath)
  const compressed = await gzipAsync(buffer)
  if (gzipCache.size >= GZIP_CACHE_LIMIT) {
    gzipCache.clear()
  }
  gzipCache.set(key, compressed)
  return compressed
}

/**
 * 尝试把磁盘文件作为响应返回。
 * 返回 true 表示已响应；false 表示文件不存在（或不是文件），由调用方决定回退/404。
 * 目录会尝试其下的 index.html（例如根路径 "/"）。
 */
async function tryServeFile(req, res, filePath, pathname) {
  let stats
  try {
    stats = await fs.stat(filePath)
  } catch {
    return false
  }
  if (stats.isDirectory()) {
    const indexPath = path.join(filePath, 'index.html')
    try {
      stats = await fs.stat(indexPath)
    } catch {
      return false
    }
    if (!stats.isFile()) {
      return false
    }
    filePath = indexPath
  }
  if (!stats.isFile()) {
    return false
  }

  const mime = contentTypeFor(filePath)
  const compressible = GZIP_MIME_TYPES.has(mime)
  const useGzip = compressible && acceptsGzip(req)
  const headers = {
    'Content-Type': withCharset(mime),
    'Cache-Control': cacheControlFor(pathname),
    'Last-Modified': stats.mtime.toUTCString(),
    'X-Content-Type-Options': 'nosniff'
  }
  if (compressible) {
    headers['Vary'] = 'Accept-Encoding'
  }

  if (useGzip) {
    const compressed = await gzipFile(filePath, stats)
    headers['Content-Encoding'] = 'gzip'
    headers['Content-Length'] = compressed.length
    res.writeHead(200, headers)
    if (req.method === 'HEAD') {
      res.end()
    } else {
      res.end(compressed)
    }
    return true
  }

  headers['Content-Length'] = stats.size
  res.writeHead(200, headers)
  if (req.method === 'HEAD') {
    res.end()
    return true
  }
  await new Promise((resolve) => {
    const stream = createReadStream(filePath)
    stream.on('error', () => {
      // 头已发出，只能中断响应，避免写出不完整内容却返回 200
      res.destroy()
      resolve()
    })
    stream.on('close', resolve)
    stream.pipe(res)
  })
  return true
}

/** 返回 SPA 入口 index.html（所有非 /assets 的未知路径回退到这里）。 */
async function serveSpaIndex(req, res) {
  let stats
  try {
    stats = await fs.stat(INDEX_FILE)
  } catch {
    sendText(res, 500, '500 Internal Server Error: frontend/dist/index.html 不存在，请先执行构建')
    return
  }
  const mime = 'text/html'
  const useGzip = acceptsGzip(req)
  const headers = {
    'Content-Type': withCharset(mime),
    'Cache-Control': 'no-cache',
    'Last-Modified': stats.mtime.toUTCString(),
    'X-Content-Type-Options': 'nosniff',
    Vary: 'Accept-Encoding'
  }
  if (useGzip) {
    const compressed = await gzipFile(INDEX_FILE, stats)
    headers['Content-Encoding'] = 'gzip'
    headers['Content-Length'] = compressed.length
    res.writeHead(200, headers)
    if (req.method === 'HEAD') {
      res.end()
    } else {
      res.end(compressed)
    }
    return
  }
  headers['Content-Length'] = stats.size
  res.writeHead(200, headers)
  if (req.method === 'HEAD') {
    res.end()
    return
  }
  await new Promise((resolve) => {
    const stream = createReadStream(INDEX_FILE)
    stream.on('error', () => {
      res.destroy()
      resolve()
    })
    stream.on('close', resolve)
    stream.pipe(res)
  })
}

/** 判断是否为需要代理到后端的 API 请求（/api 或 /api/...）。 */
function isApiRequest(pathname) {
  return pathname === '/api' || pathname.startsWith('/api/')
}

/** 过滤逐跳首部，返回可安全转发的普通对象。 */
function filterHeaders(headers) {
  const result = {}
  for (const [name, value] of Object.entries(headers)) {
    if (!HOP_BY_HOP_HEADERS.has(name.toLowerCase()) && value !== undefined) {
      result[name] = value
    }
  }
  return result
}

/**
 * /api/* 反向代理：保留 method / headers（含 Origin、Cookie、Content-Type）/ body，
 * 仅把 Host 覆写为后端地址；上游不可达或超时统一回 502。
 */
function proxyApi(req, res) {
  const headers = filterHeaders(req.headers)
  headers.host = API_HOST_HEADER
  // 便于后端排查真实来源，同时不影响 cpolar 域名的 CORS 判断（Origin 原样保留）
  headers['x-forwarded-host'] = req.headers.host || ''
  headers['x-forwarded-for'] = req.socket.remoteAddress || ''
  headers['x-forwarded-proto'] = 'http'

  const options = {
    hostname: API_TARGET.hostname,
    port: API_TARGET.port || (API_TARGET.protocol === 'https:' ? 443 : 80),
    protocol: API_TARGET.protocol,
    method: req.method,
    path: req.url,
    headers
  }

  const proxyReq = http.request(options, (proxyRes) => {
    try {
      res.writeHead(proxyRes.statusCode || 502, proxyRes.statusMessage, filterHeaders(proxyRes.headers))
    } catch {
      proxyRes.resume()
      res.destroy()
      return
    }
    if (req.method === 'HEAD') {
      proxyRes.resume()
      res.end()
      return
    }
    proxyRes.on('error', () => {
      if (!res.writableEnded && !res.destroyed) {
        res.destroy()
      }
    })
    proxyRes.pipe(res)
  })

  // 上游迟迟不响应时主动中断，按 502 处理，避免连接悬挂
  proxyReq.setTimeout(PROXY_TIMEOUT_MS, () => {
    proxyReq.destroy(new Error('upstream timeout'))
  })
  proxyReq.on('error', (err) => {
    console.error('[serve-dist] API 代理失败 ' + req.method + ' ' + req.url + ':', err.message)
    if (res.headersSent) {
      if (!res.writableEnded && !res.destroyed) {
        res.destroy()
      }
      return
    }
    sendJson(res, 502, { code: 502, message: 'Bad Gateway: 上游服务不可用' })
  })
  req.on('error', () => proxyReq.destroy())
  // 客户端在请求体传完前断开：及时释放上游连接，避免悬挂到超时
  req.on('close', () => {
    if (!req.complete) {
      proxyReq.destroy()
    }
  })
  req.pipe(proxyReq)
}

/** 请求总入口：API 代理 -> /assets 静态（缺失 404）-> 其他文件 -> SPA 回退。 */
async function handleRequest(req, res) {
  const pathname = decodePathname(req.url || '/')
  if (pathname === null) {
    sendText(res, 400, '400 Bad Request')
    return
  }

  if (isApiRequest(pathname)) {
    proxyApi(req, res)
    return
  }

  if (req.method !== 'GET' && req.method !== 'HEAD') {
    sendText(res, 405, '405 Method Not Allowed', { Allow: 'GET, HEAD' })
    return
  }

  // /assets 下的构建产物：命中返回文件，缺失直接 404，绝不回退 index.html
  if (pathname.startsWith('/assets/')) {
    const filePath = toSafeFilePath(pathname)
    if (filePath && (await tryServeFile(req, res, filePath, pathname))) {
      return
    }
    sendText(res, 404, '404 Not Found')
    return
  }

  // 其他路径：先找真实文件（含根路径 "/" -> dist/index.html），找不到再 SPA 回退
  const filePath = toSafeFilePath(pathname)
  if (filePath && (await tryServeFile(req, res, filePath, pathname))) {
    return
  }
  await serveSpaIndex(req, res)
}

const server = http.createServer((req, res) => {
  handleRequest(req, res).catch((err) => {
    console.error('[serve-dist] 请求处理异常 ' + req.method + ' ' + req.url + ':', err)
    if (!res.headersSent) {
      sendText(res, 500, '500 Internal Server Error')
    } else {
      res.destroy()
    }
  })
})

// 非法请求（如畸形请求行）不应拖垮进程
server.on('clientError', (_err, socket) => {
  if (socket.writable) {
    socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n')
  } else {
    socket.destroy()
  }
})

server.on('error', (err) => {
  if (err && err.code === 'EADDRINUSE') {
    console.error('[serve-dist] 启动失败：端口 ' + PORT + ' 已被占用')
  } else {
    console.error('[serve-dist] 启动失败:', err)
  }
  process.exit(1)
})

server.listen(PORT, HOST, () => {
  console.log('[serve-dist] pid: ' + process.pid)
  console.log('[serve-dist] dist root: ' + DIST_ROOT)
  console.log('[serve-dist] listening on http://' + HOST + ':' + PORT + ' (local: http://127.0.0.1:' + PORT + ')')
  console.log('[serve-dist] /api/* -> ' + API_TARGET.href)
})

// 收到退出信号时优雅关闭，避免端口残留占用影响重启
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    console.log('[serve-dist] 收到 ' + signal + '，正在关闭服务')
    server.close(() => process.exit(0))
    setTimeout(() => process.exit(0), 3000).unref()
  })
}
