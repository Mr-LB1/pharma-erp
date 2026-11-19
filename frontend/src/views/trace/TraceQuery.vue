<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-select
          v-model="query.productId"
          placeholder="输入编码/名称远程搜索商品"
          clearable
          filterable
          remote
          :remote-method="searchProducts"
          :loading="productLoading"
          style="width: 300px"
        >
          <el-option
            v-for="p in productOptions"
            :key="p.id"
            :label="productOptionLabel(p)"
            :value="p.id!"
          />
        </el-select>
        <el-input
          v-model="query.batchNo"
          placeholder="批号"
          clearable
          style="width: 180px"
          @keyup.enter="handleForward"
        />
        <el-button type="primary" :icon="Right" :loading="loading" @click="handleForward">
          正向追溯
        </el-button>
        <el-button type="warning" :icon="Back" :loading="loading" @click="handleBackward">
          逆向追溯
        </el-button>
      </div>
    </div>

    <el-empty v-if="!result" description="请选择商品并输入批号后查询追溯链路" />

    <div v-else class="trace-result">
      <div class="trace-head">
        <span class="trace-title">
          {{ directionLabel }}追溯链路（商品 #{{ result.productId }} / 批号 {{ result.batchNo }}）
        </span>
      </div>

      <div class="trace-section">
        <div class="section-title">来源供应商（入库）</div>
        <el-table :data="result.inbounds" border size="small" :row-class-name="voidRowClass">
          <el-table-column prop="receiveNo" label="入库单号" min-width="140" show-overflow-tooltip />
          <el-table-column prop="orderNo" label="采购订单号" min-width="140" show-overflow-tooltip />
          <el-table-column prop="supplierName" label="供应商" min-width="160" show-overflow-tooltip />
          <el-table-column prop="receiveDate" label="日期" width="120" />
          <el-table-column prop="quantity" label="数量" width="100" align="right" />
          <el-table-column label="单据状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="docTag(row.receiveStatus)">
                {{ docLabel(row.receiveStatus) }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!result.inbounds.length" description="无入库记录" :image-size="50" />
      </div>

      <div class="trace-section">
        <div class="section-title">去向客户（出库）</div>
        <el-table :data="result.outbounds" border size="small" :row-class-name="voidRowClass">
          <el-table-column prop="deliveryNo" label="出库单号" min-width="140" show-overflow-tooltip />
          <el-table-column prop="orderNo" label="销售订单号" min-width="140" show-overflow-tooltip />
          <el-table-column prop="customerName" label="客户" min-width="160" show-overflow-tooltip />
          <el-table-column prop="deliveryDate" label="日期" width="120" />
          <el-table-column prop="quantity" label="数量" width="100" align="right" />
          <el-table-column label="单据状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="docTag(row.deliveryStatus)">
                {{ docLabel(row.deliveryStatus) }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!result.outbounds.length" description="无出库记录" :image-size="50" />
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Right, Back } from '@element-plus/icons-vue'
import { traceForward, traceBackward } from '@/api/trace'
import { listProducts } from '@/api/product'
import { statusLabel, statusTag } from '@/utils/status'
import type { TagType } from '@/utils/status'
import { mergeById } from '@/utils/options'
import type { Product, TraceResult } from '@/types'

const products = ref<Product[]>([])
const productOptions = ref<Product[]>([])
const productLoading = ref(false)
const loading = ref(false)
const result = ref<TraceResult | null>(null)
const directionLabel = ref('')

const query = reactive({
  productId: undefined as number | undefined,
  batchNo: ''
})

function productOptionLabel(p: Product) {
  return `${p.code} | ${p.genericName} ${p.tradeName} ${p.spec}`
}

// 追溯链路中作废单据保留标记：灰显 + 灰色标签
function docLabel(status?: string) {
  if (!status) return '-'
  return statusLabel(status)
}

function docTag(status?: string): TagType {
  if (!status) return 'info'
  return status === 'VOID' ? 'info' : statusTag(status)
}

function voidRowClass({ row }: { row: { receiveStatus?: string; deliveryStatus?: string } }) {
  return row.receiveStatus === 'VOID' || row.deliveryStatus === 'VOID' ? 'trace-row--void' : ''
}

// 下拉远程搜索（P1-30）
async function searchProducts(keyword: string) {
  productLoading.value = true
  try {
    const data = await listProducts(keyword, 1, 50)
    const selected = products.value.find((p) => p.id === query.productId)
    productOptions.value = selected ? mergeById([selected], data.records) : data.records
    products.value = mergeById(products.value, data.records)
  } finally {
    productLoading.value = false
  }
}

async function loadOptions() {
  const p = await listProducts('', 1, 100)
  products.value = p.records
  productOptions.value = p.records
}

async function doTrace(direction: 'forward' | 'backward') {
  if (!query.productId) {
    ElMessage.warning('请选择商品')
    return
  }
  if (!query.batchNo) {
    ElMessage.warning('请输入批号')
    return
  }
  loading.value = true
  try {
    const data =
      direction === 'forward'
        ? await traceForward(query.productId, query.batchNo)
        : await traceBackward(query.productId, query.batchNo)
    result.value = data
    directionLabel.value = direction === 'forward' ? '正向' : '逆向'
    if (
      (!data.inbounds || data.inbounds.length === 0) &&
      (!data.outbounds || data.outbounds.length === 0)
    ) {
      ElMessage.info('未查询到追溯链路')
    }
  } finally {
    loading.value = false
  }
}

function handleForward() {
  doTrace('forward')
}

function handleBackward() {
  doTrace('backward')
}

onMounted(loadOptions)
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

.trace-result {
  padding: 8px 4px;
}

.trace-head {
  margin-bottom: 16px;
}

.trace-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.trace-section {
  margin-bottom: 20px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}

:deep(.trace-row--void) {
  color: var(--el-text-color-placeholder);
  text-decoration: line-through;
  background: var(--el-fill-color-lighter);
}

:deep(.trace-row--void .el-tag) {
  text-decoration: none;
}
</style>
