<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="query.keyword"
          placeholder="搜索收款单号"
          clearable
          style="width: 220px"
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select
          v-model="query.customerId"
          placeholder="输入名称远程搜索客户"
          clearable
          filterable
          remote
          :remote-method="searchCustomers"
          :loading="customerLoading"
          style="width: 240px"
          @change="handleSearch"
        >
          <el-option v-for="c in customerOptions" :key="c.id" :label="c.name" :value="c.id!" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
      </div>
      <el-button
        v-permission="'finance:receipt:create'"
        type="primary"
        :icon="Plus"
        @click="openDialog"
      >
        新建收款
      </el-button>
    </div>

    <el-table :data="list" v-loading="loading" border stripe row-key="id">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="expand-box">
            <el-table :data="row.items || []" size="small" border>
              <el-table-column label="应收单号" min-width="180" show-overflow-tooltip>
                <template #default="{ row: it }">{{ it.invoiceNo || '-' }}</template>
              </el-table-column>
              <el-table-column label="核销金额" width="140" align="right">
                <template #default="{ row: it }">{{ formatMoney(it.amount) }}</template>
              </el-table-column>
            </el-table>
            <div v-if="!row.items || row.items.length === 0" class="text-muted">
              无核销明细
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="receiptNo" label="收款单号" min-width="180" show-overflow-tooltip />
      <el-table-column label="客户" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.customerName || '-' }}</template>
      </el-table-column>
      <el-table-column label="收款金额" width="140" align="right">
        <template #default="{ row }">{{ formatMoney(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="备注" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.remark || '-' }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
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
        @size-change="handleSearch"
      />
    </div>

    <el-dialog
      v-model="dialogVisible"
      title="新建收款"
      width="860px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-form :model="form" label-width="90px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="客户" required>
              <el-select
                v-model="form.customerId"
                placeholder="输入名称远程搜索客户"
                filterable
                remote
                :remote-method="searchCustomers"
                :loading="customerLoading"
                style="width: 100%"
                @change="handleCustomerChange"
              >
                <el-option
                  v-for="c in customerOptions"
                  :key="c.id"
                  :label="c.name"
                  :value="c.id!"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="收款金额">
              <el-input :model-value="formatMoney(totalAllocated)" disabled />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="可选" />
        </el-form-item>
      </el-form>

      <div class="alloc-head">
        <span class="alloc-title">核销应收单</span>
        <el-button
          size="small"
          :icon="Refresh"
          :disabled="!form.customerId"
          :loading="invoiceLoading"
          @click="loadOpenInvoices"
        >
          刷新未收应收
        </el-button>
      </div>
      <el-table :data="openInvoices" v-loading="invoiceLoading" size="small" border max-height="340">
        <el-table-column prop="invoiceNo" label="应收单号" min-width="170" show-overflow-tooltip />
        <el-table-column label="应收金额" width="120" align="right">
          <template #default="{ row }">{{ formatMoney(row.invoiceAmount) }}</template>
        </el-table-column>
        <el-table-column label="未收金额" width="120" align="right">
          <template #default="{ row }">{{ formatMoney(remaining(row as ArInvoice)) }}</template>
        </el-table-column>
        <el-table-column label="本次核销" width="160" align="right">
          <template #default="{ row }">
            <el-input-number
              v-model="row.allocateAmount"
              :min="0"
              :max="remaining(row as ArInvoice)"
              :precision="2"
              size="small"
              style="width: 100%"
            />
          </template>
        </el-table-column>
      </el-table>
      <div v-if="form.customerId && !invoiceLoading && openInvoices.length === 0" class="text-muted empty">
        该客户暂无未结应收单
      </div>

      <template #footer>
        <span class="footer-total">合计核销：{{ formatMoney(totalAllocated) }}</span>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">确认收款</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Plus, Refresh } from '@element-plus/icons-vue'
import { listReceipts, createReceipt, listArInvoices } from '@/api/finance'
import { listCustomers } from '@/api/customer'
import { statusLabel, statusTag } from '@/utils/status'
import { formatMoney } from '@/utils/format'
import { mergeById } from '@/utils/options'
import type { Receipt, ArInvoice, Customer } from '@/types'

interface AllocRow extends ArInvoice {
  allocateAmount: number
}

const list = ref<Receipt[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ keyword: '', customerId: undefined as number | undefined, page: 1, size: 20 })

const customers = ref<Customer[]>([])
const customerOptions = ref<Customer[]>([])
const customerLoading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const invoiceLoading = ref(false)
const openInvoices = ref<AllocRow[]>([])

const form = reactive({
  customerId: undefined as number | undefined,
  remark: ''
})

const totalAllocated = computed(() =>
  openInvoices.value.reduce((sum, row) => sum + Number(row.allocateAmount || 0), 0)
)

function remaining(row: ArInvoice) {
  if (row.outstanding != null) return Number(row.outstanding)
  return Number(row.invoiceAmount || 0) - Number(row.settledAmount || 0)
}

function formatDateTime(v?: string) {
  if (!v) return '-'
  return v.replace('T', ' ').slice(0, 19)
}

async function load() {
  loading.value = true
  try {
    const data = await listReceipts({
      keyword: query.keyword || undefined,
      customerId: query.customerId,
      page: query.page,
      size: query.size
    })
    list.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  load()
}

async function searchCustomers(keyword: string) {
  customerLoading.value = true
  try {
    const data = await listCustomers(keyword, 1, 30, 'ACTIVE')
    const selected = customers.value.find((c) => c.id === form.customerId)
    customerOptions.value = selected ? mergeById([selected], data.records) : data.records
    customers.value = mergeById(customers.value, data.records)
  } finally {
    customerLoading.value = false
  }
}

function openDialog() {
  form.customerId = undefined
  form.remark = ''
  openInvoices.value = []
  dialogVisible.value = true
}

async function handleCustomerChange() {
  openInvoices.value = []
  await loadOpenInvoices()
}

async function loadOpenInvoices() {
  if (!form.customerId) return
  invoiceLoading.value = true
  try {
    const [unsettled, partial] = await Promise.all([
      listArInvoices({ customerId: form.customerId, status: 'UNSETTLED', page: 1, size: 100 }),
      listArInvoices({ customerId: form.customerId, status: 'PARTIAL', page: 1, size: 100 })
    ])
    openInvoices.value = [...unsettled.records, ...partial.records].map((row) => ({
      ...row,
      allocateAmount: 0
    }))
  } finally {
    invoiceLoading.value = false
  }
}

async function handleSave() {
  if (!form.customerId) {
    ElMessage.warning('请选择客户')
    return
  }
  const items = openInvoices.value
    .filter((row) => Number(row.allocateAmount || 0) > 0)
    .map((row) => ({ arInvoiceId: row.id!, amount: Number(row.allocateAmount) }))
  if (items.length === 0) {
    ElMessage.warning('请至少选择一张应收单并填写核销金额')
    return
  }
  for (const row of openInvoices.value) {
    if (Number(row.allocateAmount || 0) > remaining(row) + 1e-9) {
      ElMessage.warning(`应收单 ${row.invoiceNo || ''} 核销金额超过未收金额`)
      return
    }
  }
  saving.value = true
  try {
    await createReceipt({
      customerId: form.customerId,
      amount: Number(totalAllocated.value.toFixed(2)),
      remark: form.remark || null,
      items
    })
    ElMessage.success('收款成功')
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  load()
  try {
    const data = await listCustomers('', 1, 50, 'ACTIVE')
    customers.value = data.records
    customerOptions.value = data.records
  } catch {
    // 下拉加载失败不阻塞列表
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
}
.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.expand-box {
  padding: 8px 16px;
}
.text-muted {
  color: #909399;
  padding: 8px 0;
}
.alloc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 4px 0 8px;
}
.alloc-title {
  font-weight: 600;
  color: #303133;
}
.empty {
  text-align: center;
  padding: 16px 0;
}
.footer-total {
  margin-right: 16px;
  color: #303133;
  font-weight: 600;
}
</style>
