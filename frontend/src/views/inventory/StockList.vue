<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-select
          v-model="query.productId"
          placeholder="商品"
          clearable
          filterable
          remote
          :remote-method="searchProducts"
          :loading="productLoading"
          style="width: 260px"
          @change="handleSearch"
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
          style="width: 160px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-date-picker
          v-model="query.expireBefore"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="效期早于"
          style="width: 160px"
          @change="handleSearch"
        />
        <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
      </div>
    </div>

    <el-table :data="list" v-loading="loading" border stripe>
      <el-table-column label="商品" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.productName || '-' }}</template>
      </el-table-column>
      <el-table-column prop="batchNo" label="批号" width="140" />
      <el-table-column prop="produceDate" label="生产日期" width="120" />
      <el-table-column label="效期" width="130">
        <template #default="{ row }">
          <span :class="expireClass(row.expireDate)" :title="expireTip(row.expireDate)">
            {{ row.expireDate || '-' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="仓库" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ warehouseName(row.locationId) }}</template>
      </el-table-column>
      <el-table-column prop="quantity" label="数量" width="110" align="right" />
      <el-table-column label="成本" width="120" align="right">
        <template #default="{ row }">{{ formatMoney(row.unitCost) }}</template>
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
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { listStocks } from '@/api/inventory'
import { listProducts } from '@/api/product'
import { listWarehouses } from '@/api/warehouse'
import { formatMoney, daysUntil, expireLevel } from '@/utils/format'
import { mergeById } from '@/utils/options'
import { useQuerySync } from '@/composables/useQuerySync'
import type { Stock, Product, Warehouse } from '@/types'

const list = ref<Stock[]>([])
const total = ref(0)
const loading = ref(false)

const products = ref<Product[]>([])
const warehouses = ref<Warehouse[]>([])
const productOptions = ref<Product[]>([])
const productLoading = ref(false)

const query = reactive({
  productId: undefined as number | undefined,
  batchNo: '',
  expireBefore: '',
  page: 1,
  size: 20
})
// 筛选条件同步到地址栏（刷新/分享链接不丢筛选）
useQuerySync(query, ['productId', 'batchNo', 'expireBefore', 'page', 'size'], {
  numeric: ['productId', 'page', 'size']
})

// 列表展示直接用后端 VO 商品名（null 显示 '-'）
function productOptionLabel(p: Product) {
  return `${p.code} | ${p.genericName} ${p.tradeName} ${p.spec}`
}
function warehouseName(locationId?: number) {
  return warehouses.value.find((w) => w.id === locationId)?.name || (locationId ? String(locationId) : '-')
}

/** 效期标色：已过期红、≤30 天橙，其余正常（与出库批次预览语义一致） */
function expireClass(expireDate?: string | null) {
  const level = expireLevel(expireDate)
  return level && level !== 'normal' ? `expire-${level}` : ''
}

function expireTip(expireDate?: string | null) {
  const days = daysUntil(expireDate)
  if (days === null) return ''
  if (days < 0) return `已过期 ${Math.abs(days)} 天`
  if (days === 0) return '今日到期'
  return `剩余 ${days} 天`
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
  const [p, w] = await Promise.all([listProducts('', 1, 100), listWarehouses('', 1, 100)])
  products.value = p.records
  productOptions.value = p.records
  warehouses.value = w.records
}

async function load() {
  loading.value = true
  try {
    const data = await listStocks({
      productId: query.productId,
      batchNo: query.batchNo || undefined,
      expireBefore: query.expireBefore || undefined,
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
  gap: 12px;
}

.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.expire-expired {
  color: var(--el-color-danger);
  font-weight: 600;
}

.expire-near {
  color: var(--el-color-warning);
  font-weight: 600;
}
</style>
