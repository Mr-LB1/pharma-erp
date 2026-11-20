# pharma-erp 启动流程（一键启动 / 停止 / 状态）

> 适用环境：Windows 10/11 + Windows PowerShell 5.1（**不要用 pwsh 7 语法**）。
> 目标：后续每次启动 ERP 都只需执行一条命令，几秒内拉起，不需要人工排查。
> 脚本位置：`scripts\start-erp.ps1`、`scripts\stop-erp.ps1`、`scripts\status-erp.ps1`。

---

## 1. 一键启动

```powershell
# 仓库根目录执行：启动后端 + 前端（自动判断是否已运行、是否需要构建前端）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-erp.ps1

# 同时开启 cpolar 公网隧道（并打印公网地址）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-erp.ps1 -Public

# 跳过前端构建（dist 已是最新时使用，启动更快）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-erp.ps1 -SkipBuild

# 只启动后端 / 只启动前端
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-erp.ps1 -BackendOnly
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-erp.ps1 -FrontendOnly
```

脚本行为（幂等）：

| 步骤 | 行为 |
|---|---|
| 1. 基础环境 | 检查 `.tools\jdk-17`、Maven、Node.js；检查 PostgreSQL(5432)、Redis(6379) 是否 LISTEN，**只检查不拉起**，未监听时明确告警 |
| 2. 前端产物 | `frontend\dist` 不存在，或比 `frontend\src` 等最新文件旧 → 自动执行 `node node_modules\vite\bin\vite.js build`（日志 `logs\build.log`）；`-SkipBuild` 可跳过 |
| 3. 后端 | 若 8080 已 LISTEN → 提示「已在运行」并跳过；否则后台执行 `mvn -f backend\pom.xml spring-boot:run`，日志 `logs\backend.log`，轮询 8080 最多 180 秒，失败打印日志末尾 40 行并以退出码 1 退出 |
| 4. 前端 | 若 5173 已 LISTEN → 跳过；否则后台执行 `node frontend\scripts\serve-dist.mjs`，日志 `logs\frontend.log`，轮询 5173 |
| 5. 公网隧道 | 指定 `-Public` 时启动 `cpolar http 5173 -log=stdout`，日志 `logs\cpolar.log`，轮询抓取 `https://xxx.r?.cpolar.(cn/top)` 并打印；cpolar 已在运行则直接复用并打印现有地址 |
| 汇总 | 打印本地地址、公网地址、各服务 PID、日志路径、默认账号 `admin / admin123`，并把 PID 写入 `logs\pids.json` |

参数一览：

| 参数 | 说明 |
|---|---|
| `-Public`（别名 `-Tunnel`） | 同时启动 cpolar 公网隧道 |
| `-SkipBuild` | 跳过前端 dist 构建 |
| `-BackendOnly` | 只启动后端 |
| `-FrontendOnly` | 只启动前端 |

退出码：`0` 成功；`1` 环境/启动失败；`2` 参数冲突（`-BackendOnly` 与 `-FrontendOnly` 同时使用）。

---

## 2. 一键停止

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-erp.ps1

# 演练：只看会影响哪些 PID，不真正结束进程
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-erp.ps1 -DryRun
```

停止顺序与兜底策略：

1. 读取 `logs\pids.json`，先 `taskkill /T /F` 启动器（cmd 包装进程，整树带走 Maven/Java/Node），再杀记录的主进程；
2. 按端口兜底：`8080`、`5173` 的 `OwningProcess` 杀进程树；
3. 按命令行特征兜底清理残留：`java.exe`（`spring-boot:run` / `com.pharma.PharmaApplication`）、`node.exe`（`serve-dist.mjs`）、`cpolar.exe`（`http 5173`）；
4. 关闭 cpolar 公网隧道进程，删除 `logs\pids.json`。

**不会**停止 PostgreSQL(5432) 与 Redis(6379)；也不会动无关进程（杀之前会校验进程名，避免 PID 复用误杀）。

---

## 3. 状态查看

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\status-erp.ps1
```

输出内容：

| 区块 | 内容 |
|---|---|
| 端口 | 8080 / 5173 / 5432 / 6379 是否 LISTEN，占用 PID 与进程名 |
| HTTP 探测 | 前端首页 `GET http://127.0.0.1:5173/` 是否 200；后端 `POST http://127.0.0.1:8080/api/auth/login`（admin/admin123）是否返回 `code=200`（只读返回码/消息，不打印 token） |
| 公网 | cpolar 进程是否在跑；公网地址（优先 `logs\cpolar.log` 最近一条，兜底查 cpolar 本地管理页 `http://127.0.0.1:4040/`） |
| 日志 | `logs` 目录下各日志文件大小与最后写入时间、`logs\pids.json` 是否存在 |
| 构建产物 | `frontend\dist` 是否最新（落后于 `frontend\src` 会告警） |
| 结论 | ERP 运行正常 / 存在异常，并提示排查入口 |

退出码：`0` 正常；`1` 存在异常。

---

## 4. 环境依赖

| 依赖 | 端口 | 是否由脚本拉起 | 说明 |
|---|---|---|---|
| PostgreSQL 17 | 5432 | 否 | **必须已运行**，否则后端启动即失败（数据库连接异常） |
| Redis | 6379 | 否 | 建议已运行；未运行则 JWT 黑名单等能力降级 |
| Spring Boot 后端 | 8080 | 是 | 需要 JDK 17（见下节） |
| 前端静态服务 | 5173 | 是 | `node frontend\scripts\serve-dist.mjs`，`/api/*` 反代到 `127.0.0.1:8080` |
| cpolar | 无（出站） | 可选（`-Public`） | 公网隧道，authtoken 已配置 |

默认账号：`admin / admin123`。

---

## 5. 工具链路径与「为什么不能用系统 JDK 8」

| 工具 | 路径（由脚本按「仓库根/..」动态解析，不写死盘符） |
|---|---|
| JDK 17 | `<工作区>\.tools\jdk-17`（即「仓库根\..\.tools\jdk-17」） |
| Maven 3.9.16 | `<工作区>\.tools\apache-maven-3.9.16\bin\mvn.cmd` |
| Node.js | `node`（默认在 PATH 中） |
| cpolar | `C:\Program Files\cpolar\cpolar.exe` |

系统环境变量 `JAVA_HOME` 当前指向 **JDK 1.8（F:\jdk_1.8）**，而本项目是 Spring Boot 3.3.5，要求 **Java 17**：

- JDK 8 无法编译/运行 Spring Boot 3.x（class 文件版本、Jakarta EE API 均不兼容）；
- 因此启动脚本**不依赖系统 JAVA_HOME**，而是在后台命令里显式 `set "JAVA_HOME=<.tools\jdk-17>"` 后再调用 Maven，不会修改系统的环境变量；
- 手工启动时同样必须显式指定 JDK 17（可参考 `backend\run.sh` 的做法）。

敏感配置：`backend\config\local-dev.yml`（已被 `.gitignore` 忽略，含数据库口令/JWT 密钥）由后端自行加载，脚本**不读取、不输出、不复制**其内容；cpolar authtoken 位于 `%USERPROFILE%\.cpolar\cpolar.yml`，脚本只会打印公网域名，**不会打印 token**。

---

## 6. 端口与日志位置

| 服务 | 端口 | 日志 | PID 记录 |
|---|---|---|---|
| 后端 Spring Boot | 8080 | `logs\backend.log` | `logs\pids.json` → `backend` |
| 前端静态服务 | 5173 | `logs\frontend.log` | `logs\pids.json` → `frontend` |
| cpolar 公网隧道 | — | `logs\cpolar.log` | `logs\pids.json` → `cpolar` |
| 前端构建 | — | `logs\build.log` | — |
| PostgreSQL | 5432 | 由数据库自身管理 | — |
| Redis | 6379 | 由 Redis 自身管理 | — |

`logs\` 目录已在 `.gitignore` 中忽略，不会进入版本库。

---

## 7. cpolar 公网域名每次都会变

免费版 cpolar 每次重启隧道都会分配**新的随机域名**（形如 `https://xxxxxxxx.r3.cpolar.cn` 或 `https://xxxxxxxx.r2.cpolar.top`），旧地址立即失效。获取方式（任选其一）：

1. 执行 `scripts\status-erp.ps1`，看「公网地址」一行；
2. `start-erp.ps1 -Public` 启动时会打印 `公网地址：https://...`；
3. 直接看日志：`logs\cpolar.log` 中最近一条含域名的行（`Tunnel established at https://...` / `Forwarding ...`）；
4. cpolar 本地管理页：浏览器打开 `http://127.0.0.1:4040/`，页面内即包含当前隧道地址。

> 注意：不要把公网地址写进代码或文档；每次启动后重新获取。

> **域名变了会不会导致登录 403？** 不会。前端静态服务 `serve-dist.mjs` 在反代 `/api/*` 时会剥离浏览器的 `Origin` 首部，后端按普通同源请求处理，因此 cpolar 每次换域名都不用改 CORS 配置。
> （2026-11-20 修复前正是因为把 `Origin` 转发给后端，新域名 `.cpolar.cn` 不在白名单，登录接口返回 `403 Invalid CORS request`，页面提示「登录失败」。）

---

## 8. 常见故障排查

| 现象 | 优先检查 | 处理 |
|---|---|---|
| 后端 8080 未监听 / 启动脚本 180 秒超时 | `logs\backend.log` 末尾 40 行（启动脚本会自动打印） | 常见原因：PostgreSQL 未启动、5432 端口被占、`local-dev.yml` 缺失或口令错误、端口被旧进程占用 |
| 后端启动报 `NoClassDefFoundError` / 类找不到 | 是否有并发 Maven 构建（多终端同时跑 `mvn`） | **Maven 构建必须串行**：先停掉其它 mvn 进程、清理 `backend\target` 中损坏的 class 后重试；不要并行执行两个 `mvn spring-boot:run` |
| 前端 403 / 白屏 / 页面没更新 | `frontend\dist` 是否最新（`status-erp.ps1` 会提示 stale/missing） | 重新执行 `start-erp.ps1`（不要加 `-SkipBuild`）重新构建；确认构建日志 `logs\build.log` 无错误 |
| 前端首页 200 但接口 404/502 | `logs\frontend.log`（反向代理错误）、后端 8080 是否在跑 | 先启动后端；确认 `serve-dist.mjs` 的 `API_TARGET` 未被环境变量改写 |
| 登录返回 403（提示「登录失败」，接口 `/api/auth/login` 返回 `Invalid CORS request`） | 浏览器同源 POST 也会带 `Origin`，若原样转给后端，Spring Security 会拿它比对 `app.cors.allowed-origins` | 已在 `frontend\scripts\serve-dist.mjs` 反代时**剥离 Origin**，任何隧道域名（cpolar 免费版每次都变）都无需改配置；若绕过静态服务直连后端，则需把域名加进 `CORS_ALLOWED_ORIGINS` |
| 登录 `code` 非 200 | 账号密码、用户被禁用、JWT 密钥变更 | 使用 `admin/admin123`；必要时查后端日志 |
| 公网地址打不开 / 每次都不一样 | cpolar 是否在跑、`logs\cpolar.log` 最新域名 | 用 `status-erp.ps1` 取最新地址；域名变了就用新地址 |
| 启动脚本报环境变量重复键（`NO_PROXY`/`no_proxy`） | 旧脚本使用 `Start-Process` 会触发该问题 | 当前脚本已改为 .NET 直接拉起 `cmd.exe`，绕开该缺陷；不要改回 `Start-Process -RedirectStandardOutput` |
| 端口被占用但不是本项目 | `netstat -ano | findstr :8080` 确认 OwningProcess | 脚本只会杀记录在案/端口占用/命令行匹配本项目的进程；其它进程请人工处理 |

---

## 9. 修改代码后如何重启

| 改动类型 | 需要的操作 |
|---|---|
| 后端 Java / `backend\src` / `application*.yml` | 重新执行 `start-erp.ps1`（先 `stop-erp.ps1` 停后端，或只重启后端用 `-BackendOnly`）——Spring Boot 不会热加载，**改完 Java 必须重启后端** |
| 后端 `pom.xml` 依赖 | 先 `stop-erp.ps1`，再 `start-erp.ps1`（Maven 会重新解析依赖，**不要与其他 Maven 构建并行**） |
| 前端 `frontend\src` / `index.html` / `vite.config.ts` | 重新执行 `start-erp.ps1`（检测到 dist 落后会自动 `vite build`）；仅查看前端状态可 `-FrontendOnly`；**前端不是 dev server，改完必须重新 build** |
| 仅查看状态 | `status-erp.ps1` |

推荐操作顺序：

```powershell
# 1) 停掉旧进程（数据库/缓存不受影响）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-erp.ps1
# 2) 重新启动
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-erp.ps1
# 3) 确认状态
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\status-erp.ps1
```

---

## 10. 一分钟自检清单

- [ ] 5432 / 6379 已监听（数据库、缓存已启动）
- [ ] `start-erp.ps1` 输出「后端已就绪」「前端已就绪」
- [ ] `status-erp.ps1` 结论为「ERP 运行正常」
- [ ] 浏览器打开 `http://localhost:5173` 能登录（`admin/admin123`）
- [ ] 需要公网时：`start-erp.ps1 -Public`，并复制最新公网地址
