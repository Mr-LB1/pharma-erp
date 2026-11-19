<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="query.keyword"
          placeholder="搜索门店编码 / 名称 / 许可证号"
          clearable
          style="width: 320px"
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-switch v-model="showInactive" active-text="显示停用" @change="handleSearch" />
      </div>
      <el-button v-permission="'base:store:create'" type="primary" :icon="Plus" @click="openDialog()">新增门店</el-button>
    </div>

    <BatchToolbar
      :selected-count="selectedCount"
      resource-type="stores"
      resource-label="门店"
      :loading="batchLoading"
      @batch-enable="handleBatchAction('ENABLE')"
      @batch-disable="handleBatchAction('DISABLE')"
      @batch-delete="handleBatchAction('DELETE')"
      @import="importVisible = true"
    />

    <el-table
      ref="tableRef"
      :data="list"
      v-loading="loading"
      border
      stripe
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="46" fixed />
      <el-table-column prop="code" label="门店编码" width="140" fixed />
      <el-table-column prop="name" label="门店名称" min-width="180" show-overflow-tooltip />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.type === 'DIRECT' ? 'primary' : 'warning'">
            {{ typeLabel(row.type) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="关联仓库" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ warehouseName(row as Store) }}</template>
      </el-table-column>
      <el-table-column prop="licenseNo" label="许可证号" min-width="160" show-overflow-tooltip />
      <el-table-column label="许可证到期" width="170">
        <template #default="{ row }">
          <el-tag
            v-if="expireState(row.licenseExpire) === 'expired'"
            size="small"
            type="danger"
          >{{ row.licenseExpire }}</el-tag>
          <el-tag
            v-else-if="expireState(row.licenseExpire) === 'warning'"
            size="small"
            type="warning"
          >{{ row.licenseExpire }}</el-tag>
          <span v-else>{{ row.licenseExpire || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
            {{ row.status === 'ACTIVE' ? '启用' : '已停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button v-permission="'base:store:update'" link type="primary" @click="openDialog(row as Store)">编辑</el-button>
          <el-button
            v-if="row.status === 'INACTIVE'"
            v-permission="'base:store:update'"
            link
            type="success"
            :loading="isActing(row.id!, 'enable')"
            :disabled="isBlocked() && !isActing(row.id!, 'enable')"
            @click="handleEnable(row as Store)"
          >
            启用
          </el-button>
          <el-button
            v-permission="'base:store:delete'"
            link
            type="danger"
            :loading="isActing(row.id!, 'delete')"
            :disabled="isBlocked() && !isActing(row.id!, 'delete')"
            @click="handleDelete(row as Store)"
          >
            删除
          </el-button>
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
      :title="form.id ? '编辑门店' : '新增门店'"
      width="640px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="120px">
        <el-form-item label="门店编码" prop="code">
          <el-input v-model="form.code" placeholder="如 MD-0001" />
        </el-form-item>
        <el-form-item label="门店名称" prop="name">
          <el-input v-model="form.name" placeholder="门店全称" />
        </el-form-item>
        <el-form-item label="门店类型" prop="type">
          <el-select v-model="form.type" style="width: 100%">
            <el-option label="直营" value="DIRECT" />
            <el-option label="加盟" value="FRANCHISE" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联仓库" prop="warehouseId">
          <el-select
            v-model="form.warehouseId"
            placeholder="输入名称远程搜索仓库"
            style="width: 100%"
            filterable
            remote
            :remote-method="searchWarehouses"
            :loading="warehouseLoading"
          >
            <el-option
              v-for="w in warehouseOptions"
              :key="w.id"
              :label="w.name"
              :value="w.id!"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.address" placeholder="门店地址" />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="form.contactName" placeholder="联系人姓名" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.contactPhone" placeholder="联系电话" />
        </el-form-item>
        <el-form-item label="许可证号">
          <el-input v-model="form.licenseNo" placeholder="药品经营许可证号" />
        </el-form-item>
        <el-form-item label="许可证到期">
          <el-date-picker
            v-model="form.licenseExpire"
            type="date"
            value-format="YYYY-MM-DD"
            :disabled-date="disabledPastDate"
            placeholder="选择日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="启用" value="ACTIVE" />
            <el-option label="停用" value="INACTIVE" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <ImportDrawer
      v-model:visible="importVisible"
      resource-type="stores"
      resource-label="门店"
      @success="load"
    />
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type TableInstance } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import { listStores, createStore, updateStore, deleteStore } from '@/api/store'
import { listWarehouses } from '@/api/warehouse'
import BatchToolbar from '@/components/BatchToolbar.vue'
import ImportDrawer from '@/components/ImportDrawer.vue'
import { useBatchOperation } from '@/composables/useBatchOperation'
import { useRowAction } from '@/composables/useRowAction'
import { mergeById } from '@/utils/options'
import { disabledPastDate } from '@/utils/format'
import type { Store, Warehouse } from '@/types'

const { isActing, isBlocked, run } = useRowAction()

const list = ref<Store[]>([])
const total = ref(0)
const loading = ref(false)
const warehouses = ref<Warehouse[]>([])
const warehouseOptions = ref<Warehouse[]>([])
const warehouseLoading = ref(false)
const tableRef = ref<TableInstance>()
const importVisible = ref(false)
// 显示停用（含已软删除）记录，用于恢复
const showInactive = ref(false)

const query = reactive({ keyword: '', page: 1, size: 20 })

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const emptyForm = (): Store => ({
  code: '',
  name: '',
  type: 'DIRECT',
  warehouseId: undefined,
  address: '',
  contactName: '',
  contactPhone: '',
  licenseNo: '',
  licenseExpire: '',
  status: 'ACTIVE'
})
const form = reactive<Store>(emptyForm())

const formRules: FormRules = {
  code: [{ required: true, message: '请输入门店编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入门店名称', trigger: 'blur' }],
  warehouseId: [{ required: true, message: '请选择关联仓库', trigger: 'change' }]
}

function typeLabel(v: string) {
  return { DIRECT: '直营', FRANCHISE: '加盟' }[v] || v || '-'
}

function warehouseName(row: Store) {
  if (row.warehouseName) return row.warehouseName
  const w = warehouses.value.find((item) => item.id === row.warehouseId)
  return w?.name || (row.warehouseId ? String(row.warehouseId) : '-')
}

// 过期返回 expired，90 天内返回 warning，其余正常
function expireState(dateStr?: string): 'expired' | 'warning' | 'normal' {
  if (!dateStr) return 'normal'
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const target = new Date(dateStr)
  if (Number.isNaN(target.getTime())) return 'normal'
  const diffDays = Math.ceil((target.getTime() - today.getTime()) / (1000 * 60 * 60 * 24))
  if (diffDays < 0) return 'expired'
  if (diffDays <= 90) return 'warning'
  return 'normal'
}

async function loadWarehouses() {
  const data = await listWarehouses('', 1, 100)
  warehouses.value = data.records
  warehouseOptions.value = data.records
}

// 下拉远程搜索（P1-30）
async function searchWarehouses(keyword: string) {
  warehouseLoading.value = true
  try {
    const data = await listWarehouses(keyword, 1, 50)
    const selected = warehouses.value.find((w) => w.id === form.warehouseId)
    warehouseOptions.value = selected ? mergeById([selected], data.records) : data.records
    warehouses.value = mergeById(warehouses.value, data.records)
  } finally {
    warehouseLoading.value = false
  }
}

async function load() {
  loading.value = true
  try {
    const status = showInactive.value ? 'ALL' : 'ACTIVE'
    const data = await listStores(query.keyword, query.page, query.size, status)
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

function openDialog(row?: Store) {
  Object.assign(form, row ? { ...row } : emptyForm())
  dialogVisible.value = true
}

async function handleSave() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      if (form.id) {
        await updateStore(form.id, form)
        ElMessage.success('修改成功')
      } else {
        await createStore(form)
        ElMessage.success('新增成功')
      }
      dialogVisible.value = false
      load()
    } finally {
      saving.value = false
    }
  })
}

// 启用（恢复）：软删除/停用后 status=INACTIVE，通过 PUT 置回 ACTIVE
async function handleEnable(row: Store) {
  await run(
    row.id!,
    async () => {
      try {
        await updateStore(row.id!, { ...row, status: 'ACTIVE' })
      } catch {
        return
      }
      ElMessage.success('已启用')
      await load()
    },
    'enable'
  )
}

async function handleDelete(row: Store) {
  try {
    await ElMessageBox.confirm(
      `确认删除门店「${row.name}」吗？删除后不可在列表查看。`,
      '提示',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await run(
    row.id!,
    async () => {
      try {
        await deleteStore(row.id!)
      } catch {
        // 删除失败（如已被引用返回 400）的提示由 request 拦截器统一展示后端 message
        return
      }
      ElMessage.success('删除成功')
      await load()
    },
    'delete'
  )
}

// ===== 批量操作 =====
const { selectedCount, batchLoading, handleSelectionChange, handleBatchAction } = useBatchOperation({
  resource: 'stores',
  label: '门店',
  reload: load,
  clearSelection: () => tableRef.value?.clearSelection()
})

onMounted(() => {
  loadWarehouses().catch(() => {})
  load()
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
</style>
