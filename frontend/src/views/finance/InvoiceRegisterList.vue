<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="query.keyword"
          placeholder="搜索发票号"
          clearable
          style="width: 260px"
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
      </div>
      <el-button
        v-permission="'finance:invoice:create'"
        type="primary"
        :icon="Plus"
        @click="openDialog"
      >
        新增发票登记
      </el-button>
    </div>

    <el-table :data="list" v-loading="loading" border stripe row-key="id">
      <el-table-column prop="invoiceNo" label="发票号" min-width="200" show-overflow-tooltip />
      <el-table-column label="关联单据" width="120">
        <template #default="{ row }">{{ bizTypeLabel(row.bizType) }}</template>
      </el-table-column>
      <el-table-column label="关联单据ID" width="110" align="center">
        <template #default="{ row }">{{ row.bizId ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="发票金额" width="140" align="right">
        <template #default="{ row }">{{ formatMoney(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="备注" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.remark || '-' }}</template>
      </el-table-column>
      <el-table-column label="登记人" width="110">
        <template #default="{ row }">{{ row.createdBy || '-' }}</template>
      </el-table-column>
      <el-table-column label="登记时间" width="170">
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
      title="新增发票登记"
      width="560px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="发票号" prop="invoiceNo">
          <el-input v-model="form.invoiceNo" placeholder="请输入发票号码" />
        </el-form-item>
        <el-form-item label="发票金额" prop="amount">
          <el-input-number
            v-model="form.amount"
            :min="0.01"
            :precision="2"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="关联类型">
          <el-select v-model="form.bizType" clearable style="width: 100%">
            <el-option label="销售出库" value="SALES_DELIVERY" />
            <el-option label="销售退货" value="SALES_RETURN" />
            <el-option label="采购入库" value="PURCHASE_RECEIVE" />
            <el-option label="采购退货" value="PURCHASE_RETURN" />
            <el-option label="手动登记" value="MANUAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联单据ID">
          <el-input-number
            v-model="form.bizId"
            :min="1"
            :controls="false"
            placeholder="可选"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import { listInvoiceRegisters, createInvoiceRegister } from '@/api/finance'
import { statusLabel, statusTag } from '@/utils/status'
import { formatMoney } from '@/utils/format'
import type { InvoiceRegister } from '@/types'

const list = ref<InvoiceRegister[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ keyword: '', page: 1, size: 20 })

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  invoiceNo: '',
  amount: 0,
  bizType: 'MANUAL',
  bizId: undefined as number | undefined,
  remark: ''
})

const formRules: FormRules = {
  invoiceNo: [{ required: true, message: '请输入发票号', trigger: 'blur' }],
  amount: [{ required: true, message: '请输入发票金额', trigger: 'blur' }]
}

const BIZ_LABELS: Record<string, string> = {
  SALES_DELIVERY: '销售出库',
  SALES_RETURN: '销售退货',
  PURCHASE_RECEIVE: '采购入库',
  PURCHASE_RETURN: '采购退货',
  MANUAL: '手动登记'
}

function bizTypeLabel(type?: string | null) {
  if (!type) return '-'
  return BIZ_LABELS[type] || type
}

function formatDateTime(v?: string) {
  if (!v) return '-'
  return v.replace('T', ' ').slice(0, 19)
}

async function load() {
  loading.value = true
  try {
    const data = await listInvoiceRegisters(query.keyword || undefined, query.page, query.size)
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

function openDialog() {
  form.invoiceNo = ''
  form.amount = 0
  form.bizType = 'MANUAL'
  form.bizId = undefined
  form.remark = ''
  dialogVisible.value = true
}

async function handleSave() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    if (!form.amount || form.amount <= 0) {
      ElMessage.warning('发票金额必须大于 0')
      return
    }
    saving.value = true
    try {
      await createInvoiceRegister({
        invoiceNo: form.invoiceNo.trim(),
        amount: form.amount,
        bizType: form.bizType || null,
        bizId: form.bizId ?? null,
        remark: form.remark || null
      })
      ElMessage.success('登记成功')
      dialogVisible.value = false
      load()
    } finally {
      saving.value = false
    }
  })
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
  gap: 12px;
}
.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
