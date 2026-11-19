<template>
  <div class="dashboard" v-loading="loading">
    <div class="welcome">
      <div>
        <div class="welcome-title">{{ greeting }}，{{ authStore.realName || '用户' }}</div>
        <div class="welcome-sub">{{ todayText }} · 数据概览</div>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-alert
      v-if="fallbackMode"
      class="fallback-alert"
      type="info"
      :closable="false"
      show-icon
      title="工作台聚合接口暂不可用，当前数字由待审批/近效期/证照预警接口拼装；进销存金额趋势待接口就绪后展示。"
    />

    <!-- KPI 卡片 -->
    <div class="kpi-grid">
      <div
        class="kpi-card"
        :class="{ clickable: can('approval:view') }"
        @click="go('/approvals', 'approval:view')"
      >
        <div class="kpi-icon pending"><el-icon><DocumentChecked /></el-icon></div>
        <div class="kpi-body">
          <div class="kpi-label">待审批单据</div>
          <div class="kpi-value">{{ data?.pendingApprovalCount ?? 0 }}<span class="unit">单</span></div>
          <div class="kpi-hint">点击进入审批中心</div>
        </div>
      </div>
      <div
        class="kpi-card"
        :class="{ clickable: can('report:view') }"
        @click="go('/reports/near-expiry', 'report:view')"
      >
        <div class="kpi-icon expiry"><el-icon><Timer /></el-icon></div>
        <div class="kpi-body">
          <div class="kpi-label">近效期批次</div>
          <div class="kpi-value warn">{{ data?.nearExpiryCount ?? 0 }}<span class="unit">批</span></div>
          <div class="kpi-hint">30 天内到期</div>
        </div>
      </div>
      <div
        class="kpi-card"
        :class="{ clickable: can('quality:license:view') }"
        @click="go('/quality/licenses', 'quality:license:view')"
      >
        <div class="kpi-icon license"><el-icon><Warning /></el-icon></div>
        <div class="kpi-body">
          <div class="kpi-label">证照到期预警</div>
          <div class="kpi-value danger">{{ data?.licenseExpiringCount ?? 0 }}<span class="unit">家</span></div>
          <div class="kpi-hint">供应商/客户/门店</div>
        </div>
      </div>
      <div
        class="kpi-card"
        :class="{ clickable: can('report:view') }"
        @click="go('/reports/stock-summary', 'report:view')"
      >
        <div class="kpi-icon amount"><el-icon><Money /></el-icon></div>
        <div class="kpi-body">
          <div class="kpi-label">今日进销存金额</div>
          <div class="kpi-amounts">
            <span class="in">入 {{ formatMoney(data?.todayInboundAmount ?? 0) }}</span>
            <span class="out">出 {{ formatMoney(data?.todayOutboundAmount ?? 0) }}</span>
          </div>
          <div class="kpi-hint">点击查看进销存汇总</div>
        </div>
      </div>
    </div>

    <div class="main-grid">
      <!-- 趋势 -->
      <el-card shadow="never" class="panel trend-panel">
        <template #header>
          <div class="panel-head">
            <span class="panel-title">近 7 天进销存趋势</span>
            <div class="legend">
              <span class="dot in"></span>入库金额
              <span class="dot out"></span>出库金额
            </div>
          </div>
        </template>
        <div v-if="trend.length" class="chart">
          <div v-for="point in trend" :key="point.date" class="chart-col">
            <div class="bars">
              <el-tooltip :content="`入库 ${formatMoney(point.inboundAmount)}`" placement="top">
                <div class="bar in" :style="{ height: barHeight(point.inboundAmount) }"></div>
              </el-tooltip>
              <el-tooltip :content="`出库 ${formatMoney(point.outboundAmount)}`" placement="top">
                <div class="bar out" :style="{ height: barHeight(point.outboundAmount) }"></div>
              </el-tooltip>
            </div>
            <div class="x-label">{{ shortDate(point.date) }}</div>
          </div>
        </div>
        <div v-if="trend.length && trendAllZero" class="chart-note">
          后端暂未下发逐日金额，当前为占位趋势
        </div>
        <el-empty v-else-if="!trend.length" description="暂无趋势数据" :image-size="90" />
      </el-card>

      <!-- 待办 -->
      <el-card shadow="never" class="panel todo-panel">
        <template #header>
          <div class="panel-head">
            <span class="panel-title">待办事项</span>
            <el-button
              v-if="can('approval:view')"
              link
              type="primary"
              @click="router.push('/approvals')"
            >
              全部
            </el-button>
          </div>
        </template>
        <div v-if="todos.length" class="todo-list">
          <div
            v-for="(todo, idx) in todos"
            :key="`${todo.bizType}-${todo.bizId}-${idx}`"
            class="todo-item"
            @click="go('/approvals', 'approval:view')"
          >
            <div class="todo-main">
              <span class="todo-title">{{ todo.title || todo.bizType }}</span>
              <span class="todo-time">{{ shortTime(todo.createdAt) }}</span>
            </div>
            <el-tag size="small" type="warning">待审批</el-tag>
          </div>
        </div>
        <el-empty v-else description="暂无待办" :image-size="80" />
      </el-card>
    </div>

    <div class="main-grid lower">
      <!-- 商品排行 -->
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-head">
            <span class="panel-title">本月商品销售排行</span>
            <span class="panel-sub">按销售金额</span>
          </div>
        </template>
        <div v-if="topProducts.length" class="rank-list">
          <div v-for="(item, idx) in topProducts" :key="item.productId ?? idx" class="rank-item">
            <div class="rank-no" :class="`no-${idx + 1}`">{{ idx + 1 }}</div>
            <div class="rank-body">
              <div class="rank-line">
                <span class="rank-name">{{ item.productName || '-' }}</span>
                <span class="rank-amount">{{ formatMoney(item.amount) }}</span>
              </div>
              <div class="rank-bar">
                <div class="rank-bar-inner" :style="{ width: rankWidth(item.amount) }"></div>
              </div>
              <div class="rank-qty">销量 {{ formatQty(item.quantity) }}</div>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无销售排行数据" :image-size="80" />
      </el-card>

      <!-- 近效期 -->
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-head">
            <span class="panel-title">近效期预警</span>
            <el-button
              v-if="can('report:view')"
              link
              type="primary"
              @click="router.push('/reports/near-expiry')"
            >
              全部
            </el-button>
          </div>
        </template>
        <el-table :data="nearExpiry" size="small" border max-height="300">
          <el-table-column label="商品" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.productName || '-' }}</template>
          </el-table-column>
          <el-table-column prop="batchNo" label="批号" width="110" />
          <el-table-column prop="expireDate" label="效期" width="110" />
          <el-table-column label="剩余天数" width="95" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="daysTag(row.daysLeft)">
                {{ row.daysLeft == null ? '-' : `${row.daysLeft} 天` }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="库存" width="85" align="right">
            <template #default="{ row }">{{ formatQty(row.quantity) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="nearExpiry.length === 0" description="暂无近效期批次" :image-size="70" />
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Refresh,
  DocumentChecked,
  Timer,
  Warning,
  Money
} from '@element-plus/icons-vue'
import { getDashboard, listNearExpiryList, listGrossProfit } from '@/api/report'
import { listPending } from '@/api/approval'
import { listExpiringLicenses } from '@/api/quality'
import { listExpiringLicenses as listStoreExpiring } from '@/api/store'
import { useAuthStore } from '@/stores/auth'
import { formatMoney, formatQty } from '@/utils/format'
import type {
  DashboardData,
  DashboardTodo,
  DashboardNearExpiry,
  DashboardTrendPoint,
  DashboardTopProduct
} from '@/types'

const router = useRouter()
const authStore = useAuthStore()

const loading = ref(false)
const fallbackMode = ref(false)
const data = ref<DashboardData | null>(null)

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '凌晨好'
  if (hour < 12) return '上午好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const todayText = computed(() => {
  const d = new Date()
  const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 星期${week}`
})

const trend = computed<DashboardTrendPoint[]>(() => data.value?.trends || [])
const topProducts = computed<DashboardTopProduct[]>(() => data.value?.topProducts || [])
const todos = computed<DashboardTodo[]>(() => data.value?.todos || [])
const nearExpiry = computed<DashboardNearExpiry[]>(() => data.value?.nearExpiry || [])

const trendMax = computed(() => {
  const values = trend.value.flatMap((p) => [
    Number(p.inboundAmount || 0),
    Number(p.outboundAmount || 0)
  ])
  return Math.max(1, ...values)
})

const rankMax = computed(() =>
  Math.max(1, ...topProducts.value.map((p) => Number(p.amount || 0)))
)

const trendAllZero = computed(() =>
  trend.value.every(
    (p) => Number(p.inboundAmount || 0) === 0 && Number(p.outboundAmount || 0) === 0
  )
)

function barHeight(v?: number) {
  const pct = (Number(v || 0) / trendMax.value) * 100
  return `${Math.max(pct, v ? 2 : 0)}%`
}

function rankWidth(v?: number) {
  return `${Math.max((Number(v || 0) / rankMax.value) * 100, 2)}%`
}

function shortDate(date?: string) {
  if (!date) return '-'
  return date.slice(5).replace('-', '/')
}

function shortTime(v?: string) {
  if (!v) return ''
  return v.replace('T', ' ').slice(5, 16)
}

function daysTag(days?: number) {
  if (days == null) return 'info'
  if (days <= 0) return 'danger'
  if (days <= 30) return 'warning'
  return 'info'
}

function can(code: string) {
  return authStore.hasPermission(code)
}

function go(path: string, permission?: string) {
  if (permission && !can(permission)) {
    ElMessage.warning('无权限访问该模块')
    return
  }
  router.push(path)
}

async function safe<T>(p: Promise<T>, fb: T): Promise<T> {
  try {
    return await p
  } catch {
    return fb
  }
}

function last7Days(): string[] {
  const days: string[] = []
  for (let i = 6; i >= 0; i--) {
    const d = new Date()
    d.setDate(d.getDate() - i)
    const m = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    days.push(`${d.getFullYear()}-${m}-${day}`)
  }
  return days
}

function monthRange(): [string, string] {
  const now = new Date()
  const first = new Date(now.getFullYear(), now.getMonth(), 1)
  const fmt = (d: Date) => {
    const m = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    return `${d.getFullYear()}-${m}-${day}`
  }
  return [fmt(first), fmt(now)]
}

// 聚合接口未就绪时的降级拼装：复用待审批/近效期/证照预警/毛利报表既有接口
async function buildFallback(): Promise<DashboardData> {
  const [from, to] = monthRange()
  const [pending, nearExpiryList, licenses, storeLicenses, grossProfit] = await Promise.all([
    safe(listPending(1, 5, true), { records: [], total: 0, page: 1, size: 5 }),
    safe(listNearExpiryList(30, true), []),
    safe(listExpiringLicenses(60, true), []),
    safe(listStoreExpiring(60, true), []),
    safe(listGrossProfit(undefined, from, to, true), [])
  ])
  return {
    pendingApprovalCount: pending.total,
    nearExpiryCount: nearExpiryList.length,
    licenseExpiringCount: licenses.length + storeLicenses.length,
    todayInboundAmount: 0,
    todayOutboundAmount: 0,
    trends: last7Days().map((date) => ({ date, inboundAmount: 0, outboundAmount: 0 })),
    // 商品排行用毛利报表（本月）按收入降序取前 5
    topProducts: grossProfit
      .filter((g) => Number(g.revenue || 0) > 0)
      .sort((a, b) => Number(b.revenue || 0) - Number(a.revenue || 0))
      .slice(0, 5)
      .map((g) => ({
        productId: g.productId,
        productName: g.productName || g.productCode,
        quantity: g.quantity,
        amount: g.revenue
      })),
    todos: pending.records.map((item) => ({
      bizType: item.bizType,
      bizId: item.bizId,
      title: `${item.bizType === 'SALES_ORDER' ? '销售订单' : item.bizType === 'SALES_RETURN' ? '销售退货' : '采购订单'} ${item.orderNo || ''} · ${
        item.partyName || ''
      }`,
      createdAt: item.orderDate
    })),
    nearExpiry: nearExpiryList.slice(0, 8).map((item) => ({
      productId: item.productId,
      productName: `${item.genericName || ''} ${item.tradeName || ''} ${item.spec || ''}`.trim(),
      batchNo: item.batchNo,
      expireDate: item.expireDate,
      quantity: item.quantity,
      warehouseName: item.warehouseName,
      daysLeft: item.daysLeft
    }))
  }
}

async function load() {
  loading.value = true
  fallbackMode.value = false
  try {
    const raw = await getDashboard()
    const normalized: DashboardData = {
      pendingApprovalCount: Number(raw.pendingApprovalCount || 0),
      nearExpiryCount: Number(raw.nearExpiryCount || 0),
      licenseExpiringCount: Number(raw.licenseExpiringCount || 0),
      // 后端 DashboardVO 用 todayPurchaseAmount/todaySalesAmount，兼容两种命名
      todayInboundAmount: Number(raw.todayInboundAmount ?? raw.todayPurchaseAmount ?? 0),
      todayOutboundAmount: Number(raw.todayOutboundAmount ?? raw.todaySalesAmount ?? 0),
      // 趋势用后端 purchaseAmount/salesAmount 命名，兼容旧 inboundAmount/outboundAmount
      trends: raw.trends?.map((t) => ({
        date: t.date,
        inboundAmount: Number(t.inboundAmount ?? t.purchaseAmount ?? 0),
        outboundAmount: Number(t.outboundAmount ?? t.salesAmount ?? 0)
      })),
      topProducts: raw.topProducts,
      todos: raw.todos,
      nearExpiry: raw.nearExpiry
    }
    // 趋势/排行/待办/近效期后端未下发时，用既有接口补齐（保证页面完整可渲染）
    if (!normalized.trends?.length || !normalized.todos?.length || !normalized.nearExpiry?.length) {
      const extra = await buildFallback()
      normalized.trends = normalized.trends?.length ? normalized.trends : extra.trends
      normalized.todos = normalized.todos?.length ? normalized.todos : extra.todos
      normalized.nearExpiry = normalized.nearExpiry?.length ? normalized.nearExpiry : extra.nearExpiry
      normalized.topProducts = normalized.topProducts?.length
        ? normalized.topProducts
        : extra.topProducts
    }
    data.value = normalized
  } catch {
    fallbackMode.value = true
    data.value = await buildFallback()
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.dashboard {
  min-height: 200px;
}
.welcome {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.welcome-title {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
}
.welcome-sub {
  margin-top: 4px;
  font-size: 13px;
  color: #909399;
}
.fallback-alert {
  margin-bottom: 16px;
}
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}
.kpi-card {
  display: flex;
  gap: 14px;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 10px;
  padding: 18px;
  transition: box-shadow 0.2s, transform 0.2s;
}
.kpi-card.clickable {
  cursor: pointer;
}
.kpi-card.clickable:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  transform: translateY(-2px);
}
.kpi-icon {
  width: 46px;
  height: 46px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  flex-shrink: 0;
}
.kpi-icon.pending {
  background: #ecf5ff;
  color: #409eff;
}
.kpi-icon.expiry {
  background: #fdf6ec;
  color: #e6a23c;
}
.kpi-icon.license {
  background: #fef0f0;
  color: #f56c6c;
}
.kpi-icon.amount {
  background: #f0f9eb;
  color: #67c23a;
}
.kpi-body {
  flex: 1;
  min-width: 0;
}
.kpi-label {
  font-size: 13px;
  color: #909399;
}
.kpi-value {
  margin-top: 4px;
  font-size: 26px;
  font-weight: 700;
  color: #303133;
  line-height: 1.2;
}
.kpi-value.warn {
  color: #e6a23c;
}
.kpi-value.danger {
  color: #f56c6c;
}
.kpi-value .unit {
  font-size: 13px;
  font-weight: 400;
  color: #909399;
  margin-left: 2px;
}
.kpi-amounts {
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 14px;
  font-weight: 600;
}
.kpi-amounts .in {
  color: #67c23a;
}
.kpi-amounts .out {
  color: #409eff;
}
.kpi-hint {
  margin-top: 6px;
  font-size: 12px;
  color: #c0c4cc;
}
.main-grid {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
}
.main-grid.lower {
  grid-template-columns: 1fr 1.2fr;
  margin-bottom: 0;
}
.panel {
  border-radius: 10px;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.panel-title {
  font-weight: 600;
  color: #303133;
}
.panel-sub {
  font-size: 12px;
  color: #909399;
}
.legend {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: #909399;
}
.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
  margin-right: 4px;
}
.dot.in {
  background: #67c23a;
}
.dot.out {
  background: #409eff;
}
.chart {
  display: flex;
  align-items: flex-end;
  justify-content: space-around;
  height: 240px;
  padding: 8px 4px 0;
}
.chart-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex: 1;
  height: 100%;
  justify-content: flex-end;
}
.bars {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  height: 100%;
  width: 100%;
  justify-content: center;
}
.bar {
  width: 16px;
  border-radius: 4px 4px 0 0;
  min-height: 0;
  transition: height 0.3s;
}
.bar.in {
  background: linear-gradient(180deg, #95d475, #67c23a);
}
.bar.out {
  background: linear-gradient(180deg, #79bbff, #409eff);
}
.x-label {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
.chart-note {
  text-align: center;
  font-size: 12px;
  color: #c0c4cc;
  margin-top: 4px;
}
.todo-list {
  max-height: 260px;
  overflow-y: auto;
}
.todo-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 4px;
  border-bottom: 1px dashed #ebeef5;
  cursor: pointer;
}
.todo-item:last-child {
  border-bottom: none;
}
.todo-item:hover .todo-title {
  color: #409eff;
}
.todo-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.todo-title {
  font-size: 14px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.todo-time {
  font-size: 12px;
  color: #c0c4cc;
}
.rank-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.rank-item {
  display: flex;
  gap: 10px;
}
.rank-no {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: #f0f2f5;
  color: #909399;
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.rank-no.no-1 {
  background: #fef0f0;
  color: #f56c6c;
}
.rank-no.no-2 {
  background: #fdf6ec;
  color: #e6a23c;
}
.rank-no.no-3 {
  background: #ecf5ff;
  color: #409eff;
}
.rank-body {
  flex: 1;
  min-width: 0;
}
.rank-line {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
}
.rank-name {
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rank-amount {
  color: #303133;
  font-weight: 600;
  margin-left: 8px;
}
.rank-bar {
  margin-top: 6px;
  height: 6px;
  background: #f0f2f5;
  border-radius: 3px;
  overflow: hidden;
}
.rank-bar-inner {
  height: 100%;
  border-radius: 3px;
  background: linear-gradient(90deg, #79bbff, #409eff);
  transition: width 0.3s;
}
.rank-qty {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
</style>
