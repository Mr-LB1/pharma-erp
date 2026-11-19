# 冒烟脚本（curl / psql）

按批次归档的端到端冒烟脚本，用于在真实服务上验证业务流程（自动化测试见 `backend/src/test`，此处补充 HTTP 层与跨模块联动验证）。

## 约定

| 项 | 说明 |
|---|---|
| 服务地址 | 默认 `http://localhost:8080`，用环境变量 `BASE`（兼容旧名 `B`）覆盖 |
| 账号 | 脚本内使用内置演示账号 `admin` / `admin123` |
| 数据库口令 | **不落盘**：需要查库的脚本要求先 `export PGPASSWORD=...`，未设置会直接报错退出 |
| psql / redis-cli 路径 | 可用 `PSQL` / `REDIS_CLI` 覆盖（默认 Windows 安装路径） |
| 幂等 | 脚本自建带时间戳的测试数据；数据库查询类脚本会打印期望值，不做自动清理 |

```bash
export PGPASSWORD=你的本地数据库口令
bash backend/scripts/smoke-14-document-ops.sh
# 指向非默认端口
BASE=http://localhost:18080 bash backend/scripts/smoke-14-document-ops.sh
```

## 脚本清单

| 脚本 | 覆盖范围 |
|---|---|
| `smoke-02-data-integrity.sh` | 软删除引用拦截、入库合规（批号/效期/检验单号/温控）、盘点提交时点重算、不合格品限仓与销毁留痕 |
| `smoke-03-auth-permissions.sh` | 用户管理、权限下发、旧 token 失效、refresh/logout、数据隔离、成本脱敏、字段级校验错误 |
| `smoke-04-finance-returns.sh` | 应收应付生成与红冲、收付款核销、对账恒等式、退货、调拨、工作台 |
| `smoke-06-role-permissions.sh` | 角色权限读取/增删、非法权限码、ADMIN 自锁保护、审计留痕 |
| `smoke-11-jwt-blacklist.sh` | Redis jti 单 token 登出黑名单（含 Redis 不可用降级） |
| `smoke-13-void-senior-approver.sh` | 金额分级 L2 角色路由、退货专员作废他人退货 |
| `smoke-14-approval-wms.sh` | 4 类单据审批 + WMS：`DRAFT → SUBMITTED → APPROVED → PUSHED → COMPLETED`，审批不动库存、回传实量才执行、差异留痕 |
| `smoke-14-document-ops.sh` | 单据导出 / 详情 / 批量作废（部分成功）/ 批量提交返回结构 |

## 前置

1. 后端已启动（`cd backend && ./run.sh`）且 Flyway 迁移到最新版本；
2. 数据库可连通，演示数据已初始化（首次启动由 Flyway 种子脚本写入）；
3. 涉及 Redis 的脚本需本地 Redis 运行（`smoke-11`）。
