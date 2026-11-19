# 医药 B2B ERP（pharma-erp）

从 0 到 1 实现的医药流通行业 B2B ERP：覆盖 **采购 → 入库 → 库存 → 销售 → 退货 → 财务结算** 完整交易链路，内置 **GSP 合规引擎**（批号/效期/温控/首营/追溯/审计）与 **多级审批工作流**。

> 个人全栈作品集项目 · AI 辅助编码 + 人工领域审查驱动开发（详见 [工程实践](#工程实践)）

## 业务全景

| 模块 | 能力 |
|---|---|
| 主数据 | 商品/供应商/客户/仓库/门店，软删除 + 引用校验 + Excel 批量导入 |
| 采购 | 订单 → 入库（批号/效期/检验单号强制）→ 采购退货（批次精确扣减） |
| 销售 | 订单 → 出库（FEFO 自动锁批）→ 销售退货（合格回补/不合格隔离双分流） |
| 库存 | 批次库存、效期红黄标、调拨（温控校验）、盘点（提交时点重算账面） |
| 质管 | 首营审核（本人回避）、证照效期预警、不合格品登记与销毁留痕 |
| 审批 | 金额分级（L1/L2 + 角色路由）、本人回避、驳回必填理由、审批中心 |
| 财务 | 应收应付自动生成、信用额度双重拦截、收付款核销、客户对账单 |
| 报表 | 工作台 Dashboard、进销存汇总（仓库维度+金额）、毛利报表、近效期清单，均支持 Excel 导出 |
| 系统 | 用户/角色/权限管理、按钮级权限指令、数据权限、审计日志（前后值+IP） |

## GSP 合规红线（每条都有自动化测试守护）

| # | 规则 | 实现 |
|---|---|---|
| R1 | FEFO 先效期先出 | 悲观锁 `FOR UPDATE` 逐批分配，出库前事前批次预览 |
| R2 | 效期阻断 | 过期/当日到期批次拒绝出库；入库校验效期>生产日期且>今天 |
| R3 | 首营阻断 | 未过首营的供应商/商品/客户禁止开单，审批状态不可经普通编辑绕过 |
| R4 | 证照预警锁交易 | 许可证到期预警 + 交易拦截 |
| R5 | 库存不可为负 | 出库/调拨/退货全链路行锁校验，无超卖 |
| R6 | 移动加权成本 | 商品级移动加权，批次级 unit_cost 记录；作废红冲数学对称 |
| R7 | 双向追溯 | 任意批次正向追到客户、逆向追到供应商，作废单据灰显不污染真实流向 |
| R8 | 不可物理删除 | 单据只作废红冲；三张流水表（库存流水/批次追溯/成本台账）append-only |

## 技术亮点

- **并发正确性**：FEFO 出库悲观锁 + `INSERT ... ON CONFLICT` 原子占位 + number_sequence 序列表防撞号；盘点提交对涉及批次加锁后**重读账面重算盈亏**（消除"创建时点 vs 提交时点"时序缺陷）
- **审批工作流**：`approval_config` 按 bizType+金额路由 L1/L2 审批层级与角色；审批人≠制单人强制回避；驳回意见前后端双重必填
- **认证安全**：JWT（jti + token_version）——禁用/改密/登出即时失效全部会话；jti 接入 Redis 黑名单实现单 token 注销（Redis 不可用降级放行，有降级测试）；refresh 续期前端单飞处理
- **权限体系**：RBAC 60+ 权限码，前端菜单/路由/按钮三级适配；订单按制单人数据隔离 + IDOR 防护（越权 403）；成本字段按 `inventory:cost:view` 脱敏
- **财务闭环**：出库自动生成应收、入库自动生成应付，作废联动红冲；信用额度提审+出库双重校验；对账恒等式 `期末 = 期初 + 发货 − 退货 − 收款` 按业务单据日期分期

## 技术栈

```
后端：Spring Boot 3.3 · Spring Security(JWT) · Spring Data JPA · Flyway(23 个版本化迁移) · PostgreSQL 17
前端：Vue 3 · TypeScript · Vite · Element Plus(按需引入) · Pinia · Vitest
测试：后端 154 项（合规红线/并发/财务/权限全覆盖） + 前端 22 项
```

## 架构

```
浏览器 (Vue3 + Element Plus)
        │  HTTPS / JWT
        ▼
   Spring Boot 3.3（单体模块化，按业务域分包）
   system · base · purchase · sales · inventory
   quality · trace · approval · finance · reports
        │
        ▼
   PostgreSQL 17（Flyway 版本化迁移）
   批次库存 stock ＋ 三张 append-only 流水：
   stock_transaction / lot_trace / cost_ledger
```

## 快速开始

```bash
# 1. 准备 PostgreSQL 17，建库
createdb pharma_erp

# 2. 配置数据源（或使用默认 localhost:5432）
export DB_URL=jdbc:postgresql://localhost:5432/pharma_erp
export DB_USER=postgres DB_PASSWORD=你的密码

# 3. 启动后端（JDK 17，表结构由 Flyway 自动迁移）
cd backend && ./run.sh          # http://localhost:8080

# 4. 启动前端
cd frontend && npm install && npm run dev   # http://localhost:5173

# 默认账号 admin / admin123
```

运行测试：

```bash
cd backend  && mvn test          # 154 项
cd frontend && npm run test      # 22 项
```

## 工程实践

- **迁移纪律**：表结构变更 = 新增 Flyway `V{n}__*.sql`，从不修改已发布脚本
- **测试先行**：GSP 红线（R1~R8）先写测试再实现；核心账务逻辑经三轮"审查 → 整改 → 核验"文档化闭环迭代
- **AI 协作开发**：AI 生成代码 + 人工领域审查把关业务规则（GSP/财务/并发），审查发现的 60+ 问题全部闭环并有测试佐证——这也是本项目对"AI 辅助工程"的实践答案

## 已知限制

- 桌面端优先（≥1280px），移动端响应式未适配
- jti 黑名单依赖 Redis（未部署 Redis 时自动降级为放行 + tokenVersion 兜底）
- GSP 外延项（温湿度连续记录、监管码上传、双人复核）未纳入范围
