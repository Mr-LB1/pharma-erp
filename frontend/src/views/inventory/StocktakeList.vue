<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <span class="toolbar-label">盘点单列表</span>
      </div>
      <el-button
        v-permission="'inventory:stocktake:create'"
        type="primary"
        :icon="Plus"
        @click="openDialog"
      >
        新建盘点单
      </el-button>
    </div>

    <el-table :data="list" v-loading="loading" border stripe row-key="id">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="expand-box">
            <el-table :data="row.items || []" size="small" border>
              <el-table-column label="商品" min-width="180" show-overflow-tooltip>
                <template #default="{ row: it }">{{ productName(it.productId) }}</template>
              </el-table-column>
              <el-table-column prop="batchNo" label="批号" width="140" />
              <el-table-column prop="bookQty" label="账面数量" width="100" align="right" />
              <el-table-column prop="actualQty" label="实盘数量" width="100" align="right" />
              <el-table-column label="盈亏" width="100" align="right">
                <template #default="{ row: it }">
                  <span :class="it.diffQty != null && it.diffQty !== 0 ? 'diff-text' : ''">
                    {{ it.diffQty ?? '-' }}
                  </span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="stocktakeNo" label="盘点单号" min-width="180" show-overflow-tooltip />
      <el-table-column label="仓库" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ warehouseName(row.warehouseId) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdBy" label="创建人" width="100" />
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'DRAFT'"
            v-permission="'inventory:stocktake:submit'"
            link
            type="primary"
            :loading="isActing(row.id!, 'submit')"
            :disabled="isBlocked() && !isActing(row.id!, 'submit')"
            @click="handleSubmit(row as Stocktake)"
          >
            提交
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
        @size-change="handleSizeChange"
      />
    </div>

    <el-dialog
      v-model="dialogVisible"
      title="新建盘点单"
      width="900px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-form-item label="盘点仓库" prop="warehouseId">
          <el-select
            v-model="form.warehouseId"
            placeholder="输入名称远程搜索仓库"
            filterable
            remote
            :remote-method="searchWarehouses"
            :loading="warehouseLoading"
            style="width: 320px"
          >
            <el-option v-for="w in warehouseOptions" :key="w.id" :label="w.name" :value="w.id!" />
          </el-select>
        </el-form-item>

        <el-form-item label="盘点明细" required>
          <div class="items-box">
            <el-table :data="form.items" size="small" border>
              <el-table-column label="商品" min-width="240">
                <template #default="{ row }">
                  <el-select
                    v-model="row.productId"
                    placeholder="输入编码/名称远程搜索商品"
                    filterable
                    remote
                    :remote-method="searchProducts"
                    :loading="productLoading"
                    style="width: 100%"
                  >
                    <el-option
                      v-for="p in productOptions"
                      :key="p.id"
                      :label="productOptionLabel(p)"
                      :value="p.id!"
                    />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="批号" width="150">
                <template #default="{ row }">
                  <el-input v-model="row.batchNo" placeholder="批号" />
                </template>
              </el-table-column>
              <el-table-column label="生产日期" width="150">
                <template #default="{ row }">
                  <el-date-picker
                    v-model="row.produceDate"
                    type="date"
                    placeholder="盘盈新批次选填"
                    value-format="YYYY-MM-DD"
                    :disabled-date="disabledFutureDate"
                    style="width: 100%"
                  />
                </template>
              </el-table-column>
              <el-table-column label="效期" width="150">
                <template #default="{ row }">
                  <el-date-picker
                    v-model="row.expireDate"
                    type="date"
                    placeholder="盘盈新批次选填"
                    value-format="YYYY-MM-DD"
                    :disabled-date="disabledOnOrBeforeDate(row.produceDate)"
                    style="width: 100%"
                  />
                </template>
              </el-table-column>
              <el-table-column label="实盘数量" width="170">
                <template #default="{ row }">
                  <el-input-number v-model="row.actualQty" :min="0" :precision="2" style="width: 100%" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="70" align="center" fixed="right">
                <template #default="{ $index }">
                  <el-button link type="danger" @click="removeItem($index)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button class="add-item-btn" type="primary" plain :icon="Plus" @click="addItem">
              添加明细
            </el-button>
          </div>
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
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { listStocktakes, createStocktake, submitStocktake } from '@/api/inventory'
import { listWarehouses } from '@/api/warehouse'
import { listProducts } from '@/api/product'
import { statusLabel, statusTag } from '@/utils/status'
import { disabledFutureDate, disabledOnOrBeforeDate } from '@/utils/format'
import { mergeById } from '@/utils/options'
import { useRowAction } from '@/composables/useRowAction'
import { useQuerySync } from '@/composables/useQuerySync'
import type { Stocktake, Warehouse, Product } from '@/types'

const { isActing, isBlocked, run } = useRowAction()

interface ItemRow {
  productId?: number
  batchNo: string
  produceDate?: string
  expireDate?: string
  actualQty: number
}

const list = ref<Stocktake[]>([])
const total = ref(0)
const loading = ref(false)

const query = reactive({ page: 1, size: 20 })
// 分页同步到地址栏（刷新/分享链接不丢当前页）
useQuerySync(query, ['page', 'size'], { numeric: ['page', 'size'] })

const warehouses = ref<Warehouse[]>([])
const products = ref<Product[]>([])
const warehouseOptions = ref<Warehouse[]>([])
const productOptions = ref<Product[]>([])
const warehouseLoading = ref(false)
const productLoading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const emptyItem = (): ItemRow => ({
  productId: undefined,
  batchNo: '',
  produceDate: '',
  expireDate: '',
  actualQty: 0
})

const form = reactive({
  warehouseId: undefined as number | undefined,
  items: [emptyItem()] as ItemRow[]
})

const formRules: FormRules = {
  warehouseId: [{ required: true, message: '请选择盘点仓库', trigger: 'change' }]
}

function productName(id?: number) {
  const p = products.value.find((x) => x.id === id)
  return p ? `${p.genericName} ${p.tradeName}` : id ? String(id) : '-'
}

function productOptionLabel(p: Product) {
  return `${p.code} | ${p.genericName} ${p.tradeName} ${p.spec}`
}

function warehouseName(id?: number) {
  return warehouses.value.find((w) => w.id === id)?.name || (id ? String(id) : '-')
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

async function searchProducts(keyword: string) {
  productLoading.value = true
  try {
    const data = await listProducts(keyword, 1, 50)
    const selected = form.items
      .map((it) => products.value.find((p) => p.id === it.productId))
      .filter((p): p is Product => !!p)
    productOptions.value = mergeById(selected, data.records)
    products.value = mergeById(products.value, data.records)
  } finally {
    productLoading.value = false
  }
}

async function loadOptions() {
  const [w, p] = await Promise.all([listWarehouses('', 1, 100), listProducts('', 1, 100)])
  warehouses.value = w.records
  warehouseOptions.value = w.records
  products.value = p.records
  productOptions.value = p.records
}

async function load() {
  loading.value = true
  try {
    const data = await listStocktakes(query.page, query.size)
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

function openDialog() {
  form.warehouseId = undefined
  form.items = [emptyItem()]
  dialogVisible.value = true
}

function addItem() {
  form.items.push(emptyItem())
}

function removeItem(index: number) {
  form.items.splice(index, 1)
}

async function handleSave() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    if (form.items.length === 0) {
      ElMessage.warning('请至少添加一条盘点明细')
      return
    }
    for (const it of form.items) {
      if (!it.productId) {
        ElMessage.warning('请选择明细商品')
        return
      }
      if (!it.batchNo) {
        ElMessage.warning('请填写批号')
        return
      }
      if (it.actualQty < 0) {
        ElMessage.warning('实盘数量不能为负')
        return
      }
    }
    saving.value = true
    try {
      await createStocktake({
        warehouseId: form.warehouseId!,
        items: form.items.map((it) => ({
          productId: it.productId!,
          batchNo: it.batchNo,
          produceDate: it.produceDate || null,
          expireDate: it.expireDate || null,
          actualQty: it.actualQty
        }))
      })
      ElMessage.success('盘点单已创建')
      dialogVisible.value = false
      load()
    } finally {
      saving.value = false
    }
  })
}

async function handleSubmit(row: Stocktake) {
  try {
    await ElMessageBox.confirm(`确认提交盘点单「${row.stocktakeNo}」吗？提交后将生成盈亏调整。`, '提示', {
      type: 'warning'
    })
  } catch {
    return
  }
  await run(
    row.id!,
    async () => {
      await submitStocktake(row.id!)
      ElMessage.success('已提交')
      await load()
    },
    'submit'
  )
}

onMounted(() => {
  load()
  loadOptions()
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

.items-box {
  width: 100%;
}

.add-item-btn {
  margin-top: 8px;
}

.diff-text {
  color: #f56c6c;
  font-weight: 600;
}
</style>
