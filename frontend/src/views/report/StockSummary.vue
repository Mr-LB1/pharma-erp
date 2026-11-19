<template>
  <el-card shadow="never" class="page-card">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-select
          v-model="query.productId"
          placeholder="全部商品（可输入搜索）"
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
        <el-select
          v-model="query.warehouseId"
          placeholder="全部仓库"
          clearable
          filterable
          style="width: 180px"
        >
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id!" />
        </el-select>
        <el-date-picker
          v-model="query.startDate"
          type="date"
          placeholder="开始日期"
          value-format="YYYY-MM-DD"
          :disabled-date="disabledAfterDate(query.endDate)"
          style="width: 160px"
        />
        <el-date-picker
          v-model="query.endDate"
          type="date"
          placeholder="结束日期"
          value-format="YYYY-MM-DD"
          :disabled-date="disabledBeforeDate(query.startDate)"
          style="width: 160px"
        />
        <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
        <el-button :icon="Refresh" @click="handleReset">重置</el-button>
      </div>
      <div class="toolbar-right">
        <el-button
          v-permission="'report:view'"
          type="success"
          :icon="Download"
          :loading="exporting"
          @click="handleExport"
        >
          导出 Excel
        </el-button>
      </div>
    </div>

    <el-table :data="list" v-loading="loading" border stripe>
      <el-table-column prop="productCode" label="商品编码" min-width="140" show-overflow-tooltip fixed />
      <el-table-column prop="genericName" label="通用名" min-width="180" show-overflow-tooltip />
      <el-table-column prop="tradeName" label="商品名" min-width="160" show-overflow-tooltip />
      <el-table-column prop="spec" label="规格" min-width="140" show-overflow-tooltip />
      <el-table-column prop="manufacturer" label="生产厂家" min-width="200" show-overflow-tooltip />
      <el-table-column prop="openingQty" label="期初数量" width="110" align="right" />
      <el-table-column prop="inboundQty" label="入库数量" width="110" align="right" />
      <el-table-column prop="outboundQty" label="出库数量" width="110" align="right" />
      <el-table-column prop="closingQty" label="期末数量" width="110" align="right" />
      <el-table-column label="期初金额" width="130" align="right">
        <template #default="{ row }">{{ formatMoney(row.openingAmount) }}</template>
      </el-table-column>
      <el-table-column label="入库金额" width="130" align="right">
        <template #default="{ row }">{{ formatMoney(row.inboundAmount) }}</template>
      </el-table-column>
      <el-table-column label="出库金额" width="130" align="right">
        <template #default="{ row }">{{ formatMoney(row.outboundAmount) }}</template>
      </el-table-column>
      <el-table-column label="期末金额" width="130" align="right">
        <template #default="{ row }">{{ formatMoney(row.closingAmount) }}</template>
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
import { ElMessage } from 'element-plus'
import { Search, Refresh, Download } from '@element-plus/icons-vue'
import { listStockSummary, exportStockSummary } from '@/api/report'
import { listProducts } from '@/api/product'
import { listWarehouses } from '@/api/warehouse'
import { formatMoney, disabledBeforeDate, disabledAfterDate } from '@/utils/format'
import { mergeById } from '@/utils/options'
import { useQuerySync } from '@/composables/useQuerySync'
import { saveBlob, dateStamp } from '@/utils/download'
import type { StockSummaryItem, Product, Warehouse } from '@/types'

const list = ref<StockSummaryItem[]>([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
const products = ref<Product[]>([])
const productOptions = ref<Product[]>([])
const productLoading = ref(false)
const warehouses = ref<Warehouse[]>([])

const query = reactive({
  productId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  startDate: '',
  endDate: '',
  page: 1,
  size: 20
})
// 筛选条件同步到地址栏（刷新/分享链接不丢筛选）
useQuerySync(query, ['productId', 'warehouseId', 'startDate', 'endDate', 'page', 'size'], {
  numeric: ['productId', 'warehouseId', 'page', 'size']
})

function productOptionLabel(p: Product) {
  return `${p.code} | ${p.genericName} ${p.tradeName} ${p.spec}`
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
    const data = await listStockSummary({
      productId: query.productId,
      warehouseId: query.warehouseId,
      startDate: query.startDate || undefined,
      endDate: query.endDate || undefined,
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

function handleReset() {
  query.productId = undefined
  query.warehouseId = undefined
  query.startDate = ''
  query.endDate = ''
  query.page = 1
  load()
}

function handleSizeChange() {
  query.page = 1
  load()
}

async function handleExport() {
  exporting.value = true
  try {
    const blob = await exportStockSummary({
      productId: query.productId,
      warehouseId: query.warehouseId,
      startDate: query.startDate || undefined,
      endDate: query.endDate || undefined
    })
    saveBlob(blob, `进销存汇总_${dateStamp()}.xlsx`)
    ElMessage.success('导出成功')
  } catch (e) {
    if (!(e as { isAxiosError?: boolean })?.isAxiosError) {
      ElMessage.error((e as Error)?.message || '导出失败')
    }
  } finally {
    exporting.value = false
  }
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
  flex-wrap: wrap;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
