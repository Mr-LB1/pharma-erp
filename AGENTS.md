# pharma-erp 协作规约（AGENTS.md）

> 本文件是**所有 AI 协作者（含子智能体）在本仓库工作的强制入口**。

## 0. 第一步：先读 skill，再决定怎么做

动手前（读代码、跑命令、开子智能体之前）**必须先读** [`.claude/skills/pharma-erp/SKILL.md`](.claude/skills/pharma-erp/SKILL.md)。

- 该 skill 内含：入口分诊表、启动运维命令、数据库操作规范、GSP 硬性规则、开发规范、验证要求、已知坑、禁止事项。
- 处理请求时**先输出判档结论**（例：`判档：启动运维 → SKILL §3`），再执行；判档不清要先问用户，禁止自行推定。
- 优先级：**用户当轮明确指令 > `.claude/skills/pharma-erp/SKILL.md` > 其它全局 skill（ai-dev-workflow / quick-change-workflow 等）**。
- 小改动走 `quick-change-workflow`、大功能走 `ai-dev-workflow`，但**两者都要叠加本 skill 的 GSP 规则与验证要求**。

> **skill 注册说明**：本 skill 已通过 Windows 目录联接（junction）注册到用户级 skill 目录，因此会出现在 skill 清单里：
> `C:\Users\82739\.agents\skills\pharma-erp` → `<repo>\.claude\skills\pharma-erp`（同一份文件，无副本）。
> 若换了机器或仓库路径变化导致 skill 消失，重建联接即可：
> `cmd /c mklink /J "C:\Users\<用户>\.agents\skills\pharma-erp" "<repo>\.claude\skills\pharma-erp"`

## 1. 一分钟认知

医药 B2B ERP（GSP 合规），模块化单体：Spring Boot 3.3.5 + Java 17 + JPA + Flyway + PostgreSQL 17；Vue 3 + TS + Vite + Element Plus + Pinia。领域包：`system/base/purchase/sales/inventory/quality/trace/approval/finance/reports`。

## 2. 启动系统（不要手工排查环境）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-erp.ps1 -Public   # 启后端8080+前端5173+公网
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\status-erp.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-erp.ps1
```

- 必须用 `<workspace>\.tools\jdk-17`，系统 `JAVA_HOME` 是 JDK 1.8，不能用。
- 前端是生产静态服务（`serve-dist.mjs`），改了前端必须 `vite build`；后端改了 Java 必须重启。
- 详细流程见 [docs/STARTUP.md](docs/STARTUP.md)；日志在 `logs/`。

## 3. 硬红线

1. 业务单据**不做物理删除**（GSP R8），只能作废/冲红；业务代码遵守 FEFO、首营拦截、证照拦截、库存非负、移动加权成本、双向追溯。
2. 不改已应用的 Flyway 迁移（V1~V27），新变更从 `V28` 起。
3. 不打印/提交数据库口令、JWT 密钥、cpolar authtoken。
4. 不 `git push -f`；不提交 `logs/`、`backups/`、`frontend/dist`、`backend/target`、`backend/config/local-dev.yml`。
5. 未验证不得声称完成（后端接口冒烟 / 前端浏览器实测 / 数据脚本跑核对）。

## 4. 代码规范

- 后端遵守全局 `java-code-standards` + `comment-standards`：分层、构造器注入、`@Transactional(rollbackFor)`、行锁防并发、BigDecimal 金额、类/方法/步骤注释齐全（无注释视为未完成）。
- 前端：`/api` 相对路径、列表筛选导出用「已应用条件」、侧栏不加 `:unique-opened` 与展开动画、Dashboard 只发一次聚合请求。
