<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="query.keyword"
          placeholder="搜索应付单号"
          clearable
          style="width: 220px"
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select
          v-model="query.supplierId"
          placeholder="输入名称远程搜索供应商"
          clearable
          filterable
          remote
          :remote-method="searchFilterSuppliers"
          :loading="filterSupplierLoading"
          style="width: 240px"
          @change="handleSearch"
        >
          <el-option
            v-for="s in filterSupplierOptions"
            :key="s.id"
            :label="s.name"
            :value="s.id!"
          />
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
        v-permission="'finance:ap:create'"
        type="primary"
        :icon="Plus"
        @click="openDialog"
      >
        手动补录应付
      </el-button>
    </div>

    <el-table :data="list" v-loading="loading" border stripe row-key="id">
      <el-table-column prop="invoiceNo" label="应付单号" min-width="180" show-overflow-tooltip />
      <el-table-column label="供应商" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.supplierName || '-' }}</template>
      </el-table-column>
      <el-table-column label="来源" width="120">
        <template #default="{ row }">{{ bizTypeLabel(row.bizType) }}</template>
      </el-table-column>
      <el-table-column label="来源单号" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.bizNo || (row.bizId ? `#${row.bizId}` : '-') }}</template>
      </el-table-column>
      <el-table-column label="应付金额" width="130" align="right">
        <template #default="{ row }">{{ formatMoney(row.invoiceAmount) }}</template>
      </el-table-column>
      <el-table-column label="已付金额" width="130" align="right">
        <template #default="{ row }">{{ formatMoney(row.settledAmount) }}</template>
      </el-table-column>
      <el-table-column label="未付金额" width="130" align="right">
        <template #default="{ row }">
          <span class="unsettled">{{ formatMoney(unsettled(row as ApInvoice)) }}</span>
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
          <el-button link type="primary" @click="handleDetail(row as ApInvoice)">查看明细</el-button>
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

    <el-dialog
      v-model="dialogVisible"
      title="手动补录应付"
      width="520px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-form-item label="供应商" prop="supplierId">
          <el-select
            v-model="form.supplierId"
            placeholder="输入名称远程搜索供应商"
            filterable
            remote
            :remote-method="searchFormSuppliers"
            :loading="formSupplierLoading"
            style="width: 100%"
          >
            <el-option
              v-for="s in formSupplierOptions"
              :key="s.id"
              :label="s.name"
              :value="s.id!"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="应付金额" prop="invoiceAmount">
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

    <el-dialog v-model="detailVisible" title="应付单明细" width="620px">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="应付单号">{{ detail?.invoiceNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="供应商">{{ detail?.supplierName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ bizTypeLabel(detail?.bizType) }}</el-descriptions-item>
        <el-descriptions-item label="来源单号">{{ detail?.bizNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="应付金额">
          {{ formatMoney(detail?.invoiceAmount) }}
        </el-descriptions-item>
        <el-descriptions-item label="已付金额">
          {{ formatMoney(detail?.settledAmount) }}
        </el-descriptions-item>
        <el-descriptions-item label="未付金额">
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
import { listApInvoices, createApInvoice } from '@/api/finance'
import { listSuppliers } from '@/api/supplier'
import { statusLabel, statusTag } from '@/utils/status'
import { formatMoney, formatDateTime } from '@/utils/format'
import { mergeById } from '@/utils/options'
import type { ApInvoice, Supplier } from '@/types'

const list = ref<ApInvoice[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({
  keyword: '',
  supplierId: undefined as number | undefined,
  status: '',
  page: 1,
  size: 20
})

// 供应商下拉：筛选栏与补录弹窗各用一套展示选项和搜索函数，
// 否则筛选框会按弹窗的 form.supplierId 合并选项，回显成数字
const suppliers = ref<Supplier[]>([])
const filterSupplierOptions = ref<Supplier[]>([])
const formSupplierOptions = ref<Supplier[]>([])
const filterSupplierLoading = ref(false)
const formSupplierLoading = ref(false)

const dialogVisible = ref(false)
const detailVisible = ref(false)
const saving = ref(false)
const detail = ref<ApInvoice | null>(null)
const formRef = ref<FormInstance>()

const form = reactive({
  supplierId: undefined as number | undefined,
  invoiceAmount: 0,
  remark: ''
})

const formRules: FormRules = {
  supplierId: [{ required: true, message: '请选择供应商', trigger: 'change' }],
  invoiceAmount: [{ required: true, message: '请输入应付金额', trigger: 'blur' }]
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

function unsettled(row: ApInvoice) {
  if (row.outstanding != null) return Number(row.outstanding)
  return Number(row.invoiceAmount || 0) - Number(row.settledAmount || 0)
}

async function load() {
  loading.value = true
  try {
    const data = await listApInvoices({
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      supplierId: query.supplierId,
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

// 筛选栏远程搜索：保留的对象是筛选条件 query.supplierId
async function searchFilterSuppliers(keyword: string) {
  filterSupplierLoading.value = true
  try {
    const data = await listSuppliers(keyword, 1, 30, 'ACTIVE')
    const selected = suppliers.value.find((s) => s.id === query.supplierId)
    filterSupplierOptions.value = selected ? mergeById([selected], data.records) : data.records
    suppliers.value = mergeById(suppliers.value, data.records)
  } finally {
    filterSupplierLoading.value = false
  }
}

// 补录弹窗远程搜索：保留的对象是表单 form.supplierId
async function searchFormSuppliers(keyword: string) {
  formSupplierLoading.value = true
  try {
    const data = await listSuppliers(keyword, 1, 30, 'ACTIVE')
    const selected = suppliers.value.find((s) => s.id === form.supplierId)
    formSupplierOptions.value = selected ? mergeById([selected], data.records) : data.records
    suppliers.value = mergeById(suppliers.value, data.records)
  } finally {
    formSupplierLoading.value = false
  }
}

function openDialog() {
  form.supplierId = undefined
  form.invoiceAmount = 0
  form.remark = ''
  dialogVisible.value = true
}

async function handleSave() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    if (!form.invoiceAmount || form.invoiceAmount <= 0) {
      ElMessage.warning('应付金额必须大于 0')
      return
    }
    saving.value = true
    try {
      await createApInvoice({
        supplierId: form.supplierId!,
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

async function handleDetail(row: ApInvoice) {
  detail.value = row
  detailVisible.value = true
}

onMounted(async () => {
  load()
  try {
    const data = await listSuppliers('', 1, 50, 'ACTIVE')
    suppliers.value = data.records
    filterSupplierOptions.value = data.records
    formSupplierOptions.value = data.records
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
