<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="query.keyword"
          placeholder="搜索仓库名称"
          clearable
          style="width: 320px"
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
        <el-switch v-model="showInactive" active-text="显示停用" @change="handleSearch" />
      </div>
      <el-button v-permission="'warehouse:create'" type="primary" :icon="Plus" @click="openDialog()">新增仓库</el-button>
    </div>

    <BatchToolbar
      :selected-count="selectedCount"
      resource-type="warehouses"
      resource-label="仓库"
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
      <el-table-column prop="name" label="仓库名称" min-width="200" show-overflow-tooltip fixed />
      <el-table-column label="温控类型" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="tempTag(row.tempType)">{{ tempLabel(row.tempType) }}</el-tag>
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
          <el-button v-permission="'warehouse:update'" link type="primary" @click="openDialog(row as Warehouse)">编辑</el-button>
          <el-button
            v-if="row.status === 'INACTIVE'"
            v-permission="'warehouse:update'"
            link
            type="success"
            :loading="isActing(row.id!, 'enable')"
            :disabled="isBlocked() && !isActing(row.id!, 'enable')"
            @click="handleEnable(row as Warehouse)"
          >
            启用
          </el-button>
          <el-button
            v-permission="'warehouse:delete'"
            link
            type="danger"
            :loading="isActing(row.id!, 'delete')"
            :disabled="isBlocked() && !isActing(row.id!, 'delete')"
            @click="handleDelete(row as Warehouse)"
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
      :title="form.id ? '编辑仓库' : '新增仓库'"
      width="480px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="仓库名称" prop="name">
          <el-input v-model="form.name" placeholder="如 一号常温库" />
        </el-form-item>
        <el-form-item label="温控类型">
          <el-select v-model="form.tempType" style="width: 100%">
            <el-option label="常温" value="ROOM_TEMP" />
            <el-option label="阴凉" value="COOL" />
            <el-option label="冷藏" value="COLD" />
          </el-select>
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
      resource-type="warehouses"
      resource-label="仓库"
      @success="load"
    />
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type TableInstance } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import { listWarehouses, createWarehouse, updateWarehouse, deleteWarehouse } from '@/api/warehouse'
import BatchToolbar from '@/components/BatchToolbar.vue'
import ImportDrawer from '@/components/ImportDrawer.vue'
import { useBatchOperation } from '@/composables/useBatchOperation'
import { useRowAction } from '@/composables/useRowAction'
import type { Warehouse } from '@/types'

const { isActing, isBlocked, run } = useRowAction()

const list = ref<Warehouse[]>([])
const total = ref(0)
const loading = ref(false)
const tableRef = ref<TableInstance>()
const importVisible = ref(false)
// 显示停用（含已软删除）记录，用于恢复
const showInactive = ref(false)

const query = reactive({ keyword: '', page: 1, size: 20 })

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const emptyForm = (): Warehouse => ({
  name: '',
  tempType: 'ROOM_TEMP',
  status: 'ACTIVE'
})
const form = reactive<Warehouse>(emptyForm())

const formRules: FormRules = {
  name: [{ required: true, message: '请输入仓库名称', trigger: 'blur' }]
}

function tempLabel(v: string) {
  return { ROOM_TEMP: '常温', COOL: '阴凉', COLD: '冷藏' }[v] || v || '-'
}
function tempTag(v: string) {
  return { ROOM_TEMP: 'info', COOL: 'primary', COLD: 'warning' }[v] as 'info' | 'primary' | 'warning' | 'success' || 'info'
}

async function load() {
  loading.value = true
  try {
    const status = showInactive.value ? 'ALL' : 'ACTIVE'
    const data = await listWarehouses(query.keyword, query.page, query.size, status)
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

function openDialog(row?: Warehouse) {
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
        await updateWarehouse(form.id, form)
        ElMessage.success('修改成功')
      } else {
        await createWarehouse(form)
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
async function handleEnable(row: Warehouse) {
  await run(
    row.id!,
    async () => {
      try {
        await updateWarehouse(row.id!, { ...row, status: 'ACTIVE' })
      } catch {
        return
      }
      ElMessage.success('已启用')
      await load()
    },
    'enable'
  )
}

async function handleDelete(row: Warehouse) {
  try {
    await ElMessageBox.confirm(
      `确认删除仓库「${row.name}」吗？删除后不可在列表查看。`,
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
        await deleteWarehouse(row.id!)
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
  resource: 'warehouses',
  label: '仓库',
  reload: load,
  clearSelection: () => tableRef.value?.clearSelection()
})

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
