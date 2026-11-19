<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <span class="toolbar-label">待审批单据（SUBMITTED）</span>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <el-table :data="list" v-loading="loading" border stripe row-key="bizId">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="expand-box">
            <el-table v-if="row.items && row.items.length" :data="row.items" size="small" border>
              <el-table-column label="商品" min-width="220" show-overflow-tooltip>
                <template #default="{ row: it }">{{ productLabel(it as ApprovalPendingItemDetail) }}</template>
              </el-table-column>
              <el-table-column prop="quantity" label="数量" width="110" align="right" />
              <el-table-column label="单价" width="120" align="right">
                <template #default="{ row: it }">{{ money(it.unitPrice) }}</template>
              </el-table-column>
              <el-table-column label="金额" width="120" align="right">
                <template #default="{ row: it }">{{ money(itemAmount(it as ApprovalPendingItemDetail)) }}</template>
              </el-table-column>
            </el-table>
            <div v-else class="expand-empty">该单据暂无明细</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="单据类型" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="bizTypeTag(row.bizType)">{{ bizTypeLabel(row.bizType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="单号" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.orderNo || `#${row.bizId}` }}</template>
      </el-table-column>
      <el-table-column label="往来单位" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.partyName || '-' }}</template>
      </el-table-column>
      <el-table-column label="制单人" width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ row.createdBy || '-' }}</template>
      </el-table-column>
      <el-table-column label="金额" width="140" align="right">
        <template #default="{ row }">{{ formatMoney(row.totalAmount) }}</template>
      </el-table-column>
      <el-table-column prop="orderDate" label="日期" width="120" />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-tooltip v-if="isSelf(row as ApprovalPendingItem)" content="审批人不能与制单人相同，请由他人审批" placement="top">
            <span class="self-tip">不可自审</span>
          </el-tooltip>
          <template v-else>
            <el-button
              v-permission="'approval:approve'"
              link
              type="success"
              :loading="isActing(row.bizId, 'approve')"
              :disabled="isBlocked() && !isActing(row.bizId, 'approve')"
              @click="handleApprove(row as ApprovalPendingItem)"
            >
              通过
            </el-button>
            <el-button
              v-permission="'approval:approve'"
              link
              type="danger"
              :loading="isActing(row.bizId, 'reject')"
              :disabled="isBlocked() && !isActing(row.bizId, 'reject')"
              @click="handleReject(row as ApprovalPendingItem)"
            >
              驳回
            </el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="load"
        @size-change="handleSizeChange"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { listPending, approve, reject } from '@/api/approval'
import { useAuthStore } from '@/stores/auth'
import { useRowAction } from '@/composables/useRowAction'
import { useQuerySync } from '@/composables/useQuerySync'
import { formatMoney } from '@/utils/format'
import type { ApprovalPendingItem, ApprovalPendingItemDetail } from '@/types'

const auth = useAuthStore()
const { isActing, isBlocked, run } = useRowAction()
const list = ref<ApprovalPendingItem[]>([])
const total = ref(0)
const loading = ref(false)

const query = reactive({ page: 1, size: 20 })
// 分页同步到地址栏（刷新/分享链接不丢当前页）
useQuerySync(query, ['page', 'size'], { numeric: ['page', 'size'] })

const BIZ_TYPE_LABELS: Record<string, string> = {
  PURCHASE_ORDER: '采购订单',
  SALES_ORDER: '销售订单',
  SALES_RETURN: '销售退货',
  PURCHASE_RECEIVE: '采购入库',
  SALES_DELIVERY: '销售出库',
  PURCHASE_RETURN: '采购退货',
  STOCK_TRANSFER: '仓库调拨'
}

function bizTypeLabel(v: string) {
  return BIZ_TYPE_LABELS[v] || v || '-'
}

type TagType = 'primary' | 'success' | 'warning' | 'info' | 'danger'

function bizTypeTag(v: string): TagType {
  const map: Record<string, TagType> = {
    PURCHASE_ORDER: 'primary',
    SALES_ORDER: 'success',
    SALES_RETURN: 'warning',
    PURCHASE_RECEIVE: 'danger',
    SALES_DELIVERY: 'primary',
    PURCHASE_RETURN: 'warning',
    STOCK_TRANSFER: 'info'
  }
  return map[v] || 'info'
}

function money(v?: number | null) {
  return formatMoney(v)
}

// 明细商品名由后端组装（商品被删时兜底显示 ID）
function productLabel(it: ApprovalPendingItemDetail) {
  return it.productName || `#${it.productId}`
}

// 调拨单等无金额口径的单据：单价/金额均为 null，展示 '-' 而非 0
function itemAmount(it: ApprovalPendingItemDetail) {
  if (it.amount != null) return it.amount
  if (it.unitPrice == null) return undefined
  return (it.quantity || 0) * it.unitPrice
}

// 审批回避：审批人不能与制单人相同（后端同样返回 403）
function isSelf(row: ApprovalPendingItem) {
  const me = auth.user?.username
  return !!me && !!row.createdBy && me === row.createdBy
}

async function load() {
  loading.value = true
  try {
    const data = await listPending(query.page, query.size)
    list.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function handleSizeChange() {
  query.page = 1
  load()
}

async function handleApprove(row: ApprovalPendingItem) {
  let comment = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `确认通过「${bizTypeLabel(row.bizType)} ${row.orderNo || ''}」吗？审批意见选填。`,
      '通过审批',
      {
        type: 'warning',
        confirmButtonText: '确认通过',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '审批意见（选填）',
        inputValidator: () => true
      }
    )
    comment = (value || '').trim()
  } catch {
    return
  }
  await run(
    row.bizId,
    async () => {
      await approve(row.bizType, row.bizId, comment || undefined)
      ElMessage.success('已通过')
      await load()
    },
    'approve'
  )
}

async function handleReject(row: ApprovalPendingItem) {
  let comment = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `确认驳回「${bizTypeLabel(row.bizType)} ${row.orderNo || ''}」吗？驳回意见必填。`,
      '驳回审批',
      {
        type: 'warning',
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '请填写驳回意见',
        inputValidator: (v: string) => (v && v.trim() ? true : '驳回必须填写意见')
      }
    )
    comment = (value || '').trim()
  } catch {
    return
  }
  await run(
    row.bizId,
    async () => {
      await reject(row.bizType, row.bizId, comment)
      ElMessage.success('已驳回')
      await load()
    },
    'reject'
  )
}

onMounted(load)
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
  align-items: center;
  gap: 12px;
}

.toolbar-label {
  font-size: 14px;
  color: #606266;
}

.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.expand-box {
  padding: 8px 16px;
}

.expand-empty {
  padding: 8px 0;
  font-size: 13px;
  color: #909399;
}

.self-tip {
  font-size: 13px;
  color: #c0c4cc;
  cursor: not-allowed;
}
</style>
