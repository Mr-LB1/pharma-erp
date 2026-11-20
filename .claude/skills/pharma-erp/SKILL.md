---
name: pharma-erp
description: "医药 B2B ERP（pharma-erp 仓库）项目工作入口：任何在本仓库的改动、排查、启动、数据清理、验收、发布请求，动手前必须先读本 skill 并按其「入口分诊」决定处理路径；包含启动/运维脚本、数据库连接与清理、GSP 硬性规则、开发规范、验证要求、已知坑与禁止事项。触发：启动系统、登录失败、菜单/页面问题、加功能、改接口、修 bug、查数据、清数据、跑测试、提交推送、部署。"
---

# pharma-erp 项目工作入口（Skill 优先）

> **使用规则（最高优先级）**：收到任何与本仓库相关的请求，**先读本 skill，再决定怎么做**。
> 1. 按下面「§1 入口分诊」选择处理路径，回答/动作第一句先写出判档结论（例：`判档：启动运维 → §3`）。
> 2. 本 skill 与临时直觉冲突时，**以本 skill 为准**；本 skill 未覆盖的情况先做只读探查，再向用户确认，不要凭猜。
> 3. 用户当轮明确指令 > 本 skill > 其它全局 skill（如 ai-dev-workflow）。

---

## §1 入口分诊（先判档，再动手）

| 请求特征 | 路径 | 必读章节 |
|---|---|---|
| 启动/停止/看状态、服务起不来、端口、公网地址、日志 | 运维 | §3 |
| 登录失败、403/401、CORS、隧道域名相关问题 | 运维 + 已知坑 | §3、§8 |
| 查库、看数据、清测试数据、数据对不上 | 数据 | §4 |
| 修 bug / 优化 / 加字段 / 补小功能（已有模块，≤3 文件、不改表、不破坏契约） | 全局 `quick-change-workflow` + 本 skill §5/§6 | §5、§6 |
| 全新模块 / 新表 / 新状态机 / 跨模块契约变更 / 要落全套文档 | 全局 `ai-dev-workflow` + 本 skill §5/§6 | §5、§6 |
| 只要只读分析、代码走查、盘点缺陷 | 复现 → 定位 → 给证据（不改代码） | §6 |
| 写代码（哪怕零散）| 全局 `java-code-standards`、`comment-standards`（后端）/ 前端遵循本 skill §5 | §5 |
| 提交/推送/发版 | 提交与推送 | §7 |

判档不清 → 一次列清两条路线 + 【AI 推荐】+ 依据，等用户答复，禁止自行推定。

---

## §2 项目速览

- 医药 B2B ERP（GSP 合规），模块化单体。后端 `backend/`：Spring Boot 3.3.5 + Java 17 + Spring Data JPA + Flyway + PostgreSQL 17；前端 `frontend/`：Vue 3 + TS + Vite + Element Plus + Pinia + Vitest。
- 领域包：`system / base / purchase / sales / inventory / quality / trace / approval / finance / reports`。
- 鉴权：JWT + RBAC（`sys_user/sys_role/sys_permission`，权限码注解 + 前端 `v-permission`/菜单/路由守卫三处一致）。
- 迁移：`backend/src/main/resources/db/migration/V*.sql`，当前到 V27（V27 已在本地库应用，**不可修改历史迁移**，新变更从 V28 起）。
- 运维/数据脚本：`scripts/`（start-erp / stop-erp / status-erp / cleanup-test-data.sql）；流程文档 `docs/STARTUP.md`。

### GSP 硬性业务规则（改业务代码前必须遵守）

| 编号 | 规则 |
|---|---|
| R1 | 出库按**近效期优先（FEFO）**扣批次 |
| R2 | 过效期批次**禁止**出库/销售 |
| R3 | 首营未审核（`first_audit_status != APPROVED`）的商品/供应商/客户**禁止**发生业务 |
| R4 | 证照过期主体**禁止**下单/收货 |
| R5 | 库存**不得为负**（并发下用行锁/悲观锁保证） |
| R6 | 成本用**移动加权平均**，金额一律 `BigDecimal` |
| R7 | 批次**双向可追溯**（入库↔出库↔关联单据） |
| R8 | 业务单据**不做物理删除**，只能作废（VOID）/冲红 |

---

## §3 启动与运维（一条命令）

```powershell
# 启动（后端 8080 + 前端 5173 + 可选公网隧道），幂等，约 30 秒
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-erp.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-erp.ps1 -Public   # 带 cpolar 公网
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\status-erp.ps1          # 状态/公网地址/日志
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-erp.ps1            # 停止（不动 PG/Redis）
```

- 参数：`-Public`（cpolar）、`-SkipBuild`、`-BackendOnly`、`-FrontendOnly`。
- **工具链不能用系统 JAVA_HOME（它指向 JDK 1.8）**：脚本自动用 `<workspace>\.tools\jdk-17` + `apache-maven-3.9.16`；手工启动也必须显式设置 JAVA_HOME。
- 前端是**生产静态服务**（`frontend/scripts/serve-dist.mjs` 提供 dist + `/api` 反代 + SPA fallback），**不是 dev server**：改前端必须 `vite build` 后刷新，没有 HMR。
- 后端改 Java/yml 必须重启；改 pom 先停再启，**Maven 严禁并发**（并发会出现 `NoClassDefFoundError: com/pharma/common/DataScope`）。
- 日志：`logs/backend.log`、`logs/frontend.log`、`logs/cpolar.log`、`logs/build.log`；PID：`logs/pids.json`。
- 端口：后端 8080、前端 5173、PG 5432、Redis 6379。
- 账号：`admin / admin123`。
- cpolar 公网域名**每次重启都会变**，以 `status-erp.ps1` 输出为准；浏览器同源访问，不依赖 CORS 白名单（反代已剥离 Origin，见 §8）。

---

## §4 数据库（本地开发库）

- 连接：`psql -h localhost -U postgres -d pharma_erp`，客户端 `C:\Program Files\PostgreSQL\17\bin\psql.exe`。
- 口令在 `backend/config/local-dev.yml`（**已 gitignore**）：读取时用 `$env:PGPASSWORD` 注入，**禁止把口令/Token 打印到对话或提交到仓库**。
- **中文 SQL 必须走 UTF-8 文件**（`\encoding UTF8` + `psql -f`），否则 PowerShell 传参会把中文变成乱码触发 `无效的"UTF8"编码字节顺序`。
- 只读排查：`pg_stat_user_tables.n_live_tup` 快速看行数；有写操作前先想清楚是否可回滚。
- **清测试数据**：`scripts/cleanup-test-data.sql`（事务化；保留 admin/approver + 角色权限 + 审批配置 + 演示档案，清空业务数据并重置单号序列）。执行前必须先备份：
  `pg_dump -h localhost -U postgres -Fc -d pharma_erp -f backups\before_cleanup.dump`；还原 `pg_restore --clean --if-exists`。
- 生产/共享库：**禁止任何 DDL 与删除**；业务数据只能走接口作废/冲红。
- 表结构变更只能新增 `V28+` 迁移；已应用迁移不可改（Flyway validate 会失败）。

---

## §5 开发硬性规范（写码前先读）

后端（与全局 `java-code-standards`/`comment-standards` 叠加执行）：

- 分层：Controller（只做参数校验/编排）→ Service（事务与业务规则）→ Repository；DTO/VO 与实体分离，禁止实体直出接口。
- 事务：写操作 `@Transactional(rollbackFor = Exception.class)`；单据状态流转必须校验前置状态（状态机不允许跳跃）。
- 并发：库存/单据/核销等热点用 `@Lock(PESSIMISTIC_WRITE)` 行锁 + 唯一索引兜底重复提交；不要用「先查后写」裸判重。
- 幂等：财务/收货/核销类接口必须有幂等键或唯一约束。
- 金额：`BigDecimal` + `RoundingMode`；禁止 `double/float` 参与金额。
- 删除：业务表只允许 `status = 'VOID'`/冲红，禁止 `delete`（GSP R8）。
- 注释：类/字段/方法 Javadoc + 方法体步骤注释是**交付必要条件**，无注释 = 未完成。
- 日志：`@Slf4j`；业务方法有日志，异常 ERROR 带堆栈，禁止打印口令/Token。
- 安全：权限码必须与前端菜单/按钮一致；新增接口要同时在 `sys_permission` 种子（迁移）与前端路由 meta 中登记。

前端：

- 生产包走相对路径 `/api`（`VITE_API_BASE_URL` 留空），不要写死后端绝对地址。
- 列表页统一：分页 + 筛选 + 导出用「已应用查询条件」而不是表单草稿值；`keep-alive` 页面刷新用 `onActivated`。
- Element Plus 侧栏**不要加 `:unique-opened`**（用户要求多组可同时展开）；子菜单不要加展开动画（曾导致掉帧与图标不绘制）。
- Dashboard 只发一次 `GET /api/reports/dashboard`，不要加多接口兜底。

---

## §6 验证要求（不验证 = 不交付）

| 改动 | 必做验证 |
|---|---|
| 后端 Java | `mvn -q compile`（必要时 `mvn test`）+ 接口冒烟：登录拿 token 后调对应接口确认 `code=200` |
| 后端 yml/迁移 | 重启后端看 `logs/backend.log` 出现 `Started PharmaApplication` + Flyway 校验通过 |
| 前端 | `vite build` 成功 + 浏览器实际点一遍（无 console 报错、目标交互可用） |
| 数据脚本 | 先备份、在事务里跑、跑完跑核对 SQL、必要时 `pg_restore` 演练 |
| 提交前 | `git status` 干净、只提交本次相关文件、`git diff` 自查无调试残留 |

前端改动后记得 `start-erp.ps1`（dist 落后会自动 build）再刷新页面；后端改动后必须重启。

---

## §7 提交与推送

- 提交信息：`type(scope): 中文摘要`（`fix/feat/chore/docs/refactor/test`），正文写清「问题 → 原因 → 改法 → 验证」。
- 一次提交只做一件事；不提交 `logs/`、`backups/`、`frontend/dist`、`backend/target`、`backend/config/local-dev.yml`。
- 推送：`git push origin main`；HTTPS 443 偶发 `Connection was reset` 时改用
  `git push ssh://git@ssh.github.com:443/Mr-LB1/pharma-erp.git main`，再
  `git fetch ssh://git@ssh.github.com:443/Mr-LB1/pharma-erp.git main:refs/remotes/origin/main` 同步追踪分支。
- **禁止 `git push -f`**；推送后核对 `git status -sb` 与 `git ls-remote origin refs/heads/main`。

---

## §8 已知坑（踩过，别再踩）

| 现象 | 根因 | 处理 |
|---|---|---|
| 公网域名登录「登录失败」，接口 403 `Invalid CORS request` | 浏览器同源 POST 也带 `Origin`，反代原样转给后端，Spring Security 拿它比对 `app.cors.allowed-origins`，新隧道域名不在白名单 | 已修：`serve-dist.mjs` 反代时**剥离 Origin**；白名单加 `*.cpolar.cn`。直连后端时才需改 `CORS_ALLOWED_ORIGINS` |
| 侧栏展开掉帧、系统管理图标不绘制 | Element Plus 子菜单 `max-height/padding 0.3s` 动画 + 复杂 SVG 路径在本机合成层失效 | 已修：去掉子菜单过渡、系统管理图标改 `Tools`（角色管理 `SetUp`）；**不要加回动画或 `:unique-opened`** |
| 登录后进工作台很慢（公网） | Vite dev 未打包，隧道上加载 33 个模块 | 用生产构建 + `serve-dist.mjs`（warm ~0.4s；冷启动 ~8s 属正常） |
| 脚本里 `Start-Process` 报 `NO_PROXY/no_proxy` 重复键 | 本机环境变量大小写重复 | 改用 .NET `ProcessStartInfo`/或用 `cmd /c` 重定向，别用 `-RedirectStandardOutput` |
| 改了 `.ps1` 后脚本莫名语法错误 | PowerShell 5.1 需要 UTF-8 **带 BOM** 才能正确解析中文 | 用带 BOM 保存；`tools.edit` 会去 BOM，改完要补回 |
| 中文 SQL 报 `无效的"UTF8"编码字节顺序` | PowerShell 传参编码问题 | 写 `.sql` 文件（`\encoding UTF8`）后 `psql -f` |
| 后端启动 `NoClassDefFoundError` | 并发 Maven 构建互相覆盖 `target` | Maven 串行；必要时清 `backend/target` 重来 |
| 报表状态筛选查不到数据 | 旧数据状态是 `ACTIVE`，V26 已归一为 `COMPLETED` | 统计口径用 `IN ('COMPLETED','ACTIVE')` |

---

## §9 禁止事项

1. 打印或提交数据库口令、JWT 密钥、cpolar authtoken。
2. 对生产/共享库做 DDL 或删除；对业务数据做物理删除（违反 GSP R8）。
3. 修改已应用的 Flyway 迁移文件（V1~V27）；新变更只能加 `V28+`。
4. `git push -f`；提交日志、备份、构建产物、本地配置。
5. 未验证就声称"已修复/已完成"；未读相关代码就改代码。
6. 把前端当 dev server 用（改完必须 build）；并发跑 Maven。

---

## §10 命令速查

```powershell
# 启动 / 状态 / 停止
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-erp.ps1 -Public
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\status-erp.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-erp.ps1

# 手动起后端（务必用 .tools jdk-17）
$env:JAVA_HOME='<workspace>\.tools\jdk-17'
& '<workspace>\.tools\apache-maven-3.9.16\bin\mvn.cmd' -f backend\pom.xml spring-boot:run

# 前端构建
cd frontend; node node_modules\vite\bin\vite.js build

# 数据库
$env:PGPASSWORD='<local-dev.yml 中的口令>'
& 'C:\Program Files\PostgreSQL\17\bin\psql.exe' -h localhost -U postgres -d pharma_erp -c "select 1"

# 备份 / 还原 / 清理
& 'C:\Program Files\PostgreSQL\17\bin\pg_dump.exe' -h localhost -U postgres -Fc -d pharma_erp -f backups\backup.dump
& 'C:\Program Files\PostgreSQL\17\bin\pg_restore.exe' -h localhost -U postgres -d pharma_erp --clean --if-exists backups\backup.dump
& 'C:\Program Files\PostgreSQL\17\bin\psql.exe' -h localhost -U postgres -d pharma_erp -v ON_ERROR_STOP=1 -f scripts\cleanup-test-data.sql
```
