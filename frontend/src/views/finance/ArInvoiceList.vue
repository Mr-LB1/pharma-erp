<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="query.keyword"
          placeholder="搜索应收单号"
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
        <el-select
          v-model="query.status"
          placeholder="结算状态"
          clearable
          style="width: 130px"
          @change="handleSearch"
        >
          <el-option label="未结算" value="UNSETTLED" />
          <el-option label="部分结算" value="PARTIAL" />
          <el-option label="已结算" value="SETTLED" />
          <el-option label="已作废" value="VOID" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
      </div>
      <el-button
        v-permission="'finance:ar:create'"
        type="primary"
        :icon="Plus"
        @click="openDialog"
      >
        手动补录应收
      </el-button>
    </div>

    <el-table :data="list" v-loading="loading" border stripe row-key="id">
      <el-table-column prop="invoiceNo" label="应收单号" min-width="180" show-overflow-tooltip />
      <el-table-column label="客户" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.customerName || '-' }}</template>
      </el-table-column>
      <el-table-column label="来源" width="120">
        <template #default="{ row }">{{ bizTypeLabel(row.bizType) }}</template>
      </el-table-column>
      <el-table-column label="来源单号" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.bizNo || (row.bizId ? `#${row.bizId}` : '-') }}</template>
      </el-table-column>
      <el-table-column label="应收金额" width="130" align="right">
        <template #default="{ row }">{{ formatMoney(row.invoiceAmount) }}</template>
      </el-table-column>
      <el-table-column label="已收金额" width="130" align="right">
        <template #default="{ row }">{{ formatMoney(row.settledAmount) }}</template>
      </el-table-column>
      <el-table-column label="未收金额" width="130" align="right">
        <template #default="{ row }">
          <span class="unsettled">{{ formatMoney(unsettled(row as ArInvoice)) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="handleDetail(row as ArInvoice)">查看明细</el-button>
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
        @size-change="handleSearch"
      />
    </div>

    <!-- 手动补录 -->
    <el-dialog
      v-model="dialogVisible"
      title="手动补录应收"
      width="520px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-form-item label="客户" prop="customerId">
          <el-select
            v-model="form.customerId"
            placeholder="输入名称远程搜索客户"
            filterable
            remote
            :remote-method="searchCustomers"
            :loading="customerLoading"
            style="width: 100%"
          >
            <el-option v-for="c in customerOptions" :key="c.id" :label="c.name" :value="c.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="应收金额" prop="invoiceAmount">
          <el-input-number
            v-model="form.invoiceAmount"
            :min="0.01"
            :precision="2"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="补录原因/说明（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 明细 -->
    <el-dialog v-model="detailVisible" title="应收单明细" width="620px">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="应收单号">{{ detail?.invoiceNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="客户">{{ detail?.customerName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="来源">
          {{ bizTypeLabel(detail?.bizType) }}
        </el-descriptions-item>
        <el-descriptions-item label="来源单号">{{ detail?.bizNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="应收金额">
          {{ formatMoney(detail?.invoiceAmount) }}
        </el-descriptions-item>
        <el-descriptions-item label="已收金额">
          {{ formatMoney(detail?.settledAmount) }}
        </el-descriptions-item>
        <el-descriptions-item label="未收金额">
          {{ formatMoney(detail ? unsettled(detail) : null) }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small" :type="statusTag(detail?.status)">
            {{ statusLabel(detail?.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="制单人">{{ detail?.createdBy || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">
          {{ formatDateTime(detail?.createdAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{ detail?.remark || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import { listArInvoices, createArInvoice } from '@/api/finance'
import { listCustomers } from '@/api/customer'
import { statusLabel, statusTag } from '@/utils/status'
import { formatMoney, formatDateTime } from '@/utils/format'
import { mergeById } from '@/utils/options'
import type { ArInvoice, Customer } from '@/types'

const list = ref<ArInvoice[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({
  keyword: '',
  customerId: undefined as number | undefined,
  status: '',
  page: 1,
  size: 20
})

const customers = ref<Customer[]>([])
const customerOptions = ref<Customer[]>([])
const customerLoading = ref(false)

const dialogVisible = ref(false)
const detailVisible = ref(false)
const saving = ref(false)
const detail = ref<ArInvoice | null>(null)
const formRef = ref<FormInstance>()

const form = reactive({
  customerId: undefined as number | undefined,
  invoiceAmount: 0,
  remark: ''
})

const formRules: FormRules = {
  customerId: [{ required: true, message: '请选择客户', trigger: 'change' }],
  invoiceAmount: [{ required: true, message: '请输入应收金额', trigger: 'blur' }]
}

const BIZ_LABELS: Record<string, string> = {
  SALES_DELIVERY: '销售出库',
  SALES_RETURN: '销售退货',
  PURCHASE_RECEIVE: '采购入库',
  PURCHASE_RETURN: '采购退货',
  MANUAL: '手动补录'
}

function bizTypeLabel(type?: string | null) {
  if (!type) return '-'
  return BIZ_LABELS[type] || type
}

function unsettled(row: ArInvoice) {
  if (row.outstanding != null) return Number(row.outstanding)
  return Number(row.invoiceAmount || 0) - Number(row.settledAmount || 0)
}

async function load() {
  loading.value = true
  try {
    const data = await listArInvoices({
      keyword: query.keyword || undefined,
      status: query.status || undefined,
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
  form.invoiceAmount = 0
  form.remark = ''
  dialogVisible.value = true
}

async function handleSave() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    if (!form.invoiceAmount || form.invoiceAmount <= 0) {
      ElMessage.warning('应收金额必须大于 0')
      return
    }
    saving.value = true
    try {
      await createArInvoice({
        customerId: form.customerId!,
        amount: form.invoiceAmount,
        remark: form.remark || null
      })
      ElMessage.success('补录成功')
      dialogVisible.value = false
      load()
    } finally {
      saving.value = false
    }
  })
}

async function handleDetail(row: ArInvoice) {
  detail.value = row
  detailVisible.value = true
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
  flex-wrap: wrap;
}
.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.unsettled {
  color: #e6a23c;
  font-weight: 600;
}
</style>
