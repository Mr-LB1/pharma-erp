<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-select
          v-model="customerId"
          placeholder="输入名称远程搜索客户"
          filterable
          remote
          :remote-method="searchCustomers"
          :loading="customerLoading"
          style="width: 260px"
        >
          <el-option v-for="c in customerOptions" :key="c.id" :label="c.name" :value="c.id!" />
        </el-select>
        <el-date-picker
          v-model="range"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          :disabled-date="disabledFutureDate"
          style="width: 280px"
        />
        <el-button type="primary" :icon="Search" :loading="loading" @click="handleQuery">
          查询对账
        </el-button>
      </div>
    </div>

    <template v-if="statement">
      <div class="summary">
        <div class="summary-item">
          <div class="label">期初应收</div>
          <div class="value">{{ formatMoney(statement.openingBalance) }}</div>
        </div>
        <div class="summary-item">
          <div class="label">本期发货</div>
          <div class="value delivery">{{ formatMoney(statement.deliveryAmount) }}</div>
        </div>
        <div class="summary-item">
          <div class="label">本期退货</div>
          <div class="value return">{{ formatMoney(statement.returnAmount) }}</div>
        </div>
        <div class="summary-item">
          <div class="label">本期收款</div>
          <div class="value receipt">{{ formatMoney(statement.receiptAmount) }}</div>
        </div>
        <div class="summary-item">
          <div class="label">期末应收</div>
          <div class="value closing">{{ formatMoney(statement.closingBalance) }}</div>
        </div>
      </div>

      <el-table :data="statementRows" border stripe v-loading="loading">
        <el-table-column label="日期" width="120">
          <template #default="{ row }">{{ row.date || '-' }}</template>
        </el-table-column>
        <el-table-column label="业务类型" width="130">
          <template #default="{ row }">{{ bizTypeLabel(row.type) }}</template>
        </el-table-column>
        <el-table-column label="单据号" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.bizNo || '-' }}</template>
        </el-table-column>
        <el-table-column label="摘要" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>
        <el-table-column label="增加应收" width="140" align="right">
          <template #default="{ row }">
            {{ row.signed > 0 ? formatMoney(row.signed) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="减少应收" width="140" align="right">
          <template #default="{ row }">
            {{ row.signed < 0 ? formatMoney(-row.signed) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="余额" width="140" align="right">
          <template #default="{ row }">{{ formatMoney(row.balance) }}</template>
        </el-table-column>
      </el-table>
      <div v-if="statementRows.length === 0" class="empty">该区间暂无往来明细</div>
    </template>
    <el-empty v-else description="请选择客户与日期区间后查询" />
  </el-card>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { getArStatement } from '@/api/finance'
import { listCustomers } from '@/api/customer'
import { formatMoney, disabledFutureDate } from '@/utils/format'
import type { ArStatement, Customer, StatementItem } from '@/types'

interface StatementRow extends StatementItem {
  signed: number
  balance: number
}

const customerId = ref<number>()
const customerOptions = ref<Customer[]>([])
const customerLoading = ref(false)
const loading = ref(false)
const statement = ref<ArStatement | null>(null)

function defaultRange(): [string, string] {
  const now = new Date()
  const first = new Date(now.getFullYear(), now.getMonth(), 1)
  const fmt = (d: Date) => {
    const m = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    return `${d.getFullYear()}-${m}-${day}`
  }
  return [fmt(first), fmt(now)]
}

const range = ref<[string, string]>(defaultRange())

const BIZ_LABELS: Record<string, string> = {
  DELIVERY: '销售发货',
  RETURN: '销售退货',
  RECEIPT: '收款',
  MANUAL: '手动补录'
}

function bizTypeLabel(type?: string | null) {
  if (!type) return '-'
  return BIZ_LABELS[type] || type
}

// 按 期初 + 发货 - 退货 - 收款 逐行推算余额
// 后端 RECEIPT 行 amount 已取负；这里统一用绝对值判方向，兼容正负两种下发
const statementRows = computed<StatementRow[]>(() => {
  const current = statement.value
  if (!current) return []
  let balance = Number(current.openingBalance || 0)
  return (current.items || []).map((item) => {
    const abs = Math.abs(Number(item.amount || 0))
    const signed = item.type === 'DELIVERY' ? abs : -abs
    balance = Number((balance + signed).toFixed(2))
    return { ...item, signed, balance }
  })
})

async function searchCustomers(keyword: string) {
  customerLoading.value = true
  try {
    const data = await listCustomers(keyword, 1, 30, 'ACTIVE')
    customerOptions.value = data.records
  } finally {
    customerLoading.value = false
  }
}

async function handleQuery() {
  if (!customerId.value) {
    ElMessage.warning('请选择客户')
    return
  }
  if (!range.value || range.value.length !== 2) {
    ElMessage.warning('请选择日期区间')
    return
  }
  loading.value = true
  try {
    statement.value = await getArStatement(customerId.value, range.value[0], range.value[1])
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    const data = await listCustomers('', 1, 50, 'ACTIVE')
    customerOptions.value = data.records
  } catch {
    // 下拉加载失败不阻塞查询
  }
})
</script>

<style scoped>
.page-card {
  border-radius: 8px;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.toolbar-left {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.summary-item {
  background: #f7f9fc;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px 16px;
}
.summary-item .label {
  color: #909399;
  font-size: 13px;
  margin-bottom: 6px;
}
.summary-item .value {
  font-size: 19px;
  font-weight: 600;
  color: #303133;
}
.summary-item .value.delivery {
  color: #409eff;
}
.summary-item .value.return {
  color: #f56c6c;
}
.summary-item .value.receipt {
  color: #67c23a;
}
.summary-item .value.closing {
  color: #e6a23c;
}
.empty {
  text-align: center;
  color: #909399;
  padding: 16px 0;
}
</style>
