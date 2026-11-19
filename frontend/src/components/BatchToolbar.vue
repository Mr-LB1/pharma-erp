<template>
  <div class="batch-toolbar" :data-resource="resourceType">
    <div class="batch-toolbar__info">
      <el-tag v-if="selectedCount > 0" type="primary" effect="dark" round size="small">
        已选 {{ selectedCount }} 条
      </el-tag>
      <span v-else class="batch-toolbar__hint">勾选列表左侧复选框可批量操作</span>
    </div>

    <div class="batch-toolbar__actions">
      <el-button
        v-permission="`${permissionPrefix}:update`"
        size="small"
        :disabled="disabled || selectedCount === 0"
        :loading="loading"
        :title="`批量启用${resourceLabel || ''}`"
        @click="emit('batch-enable')"
      >
        批量启用
      </el-button>
      <el-button
        v-permission="`${permissionPrefix}:update`"
        size="small"
        :disabled="disabled || selectedCount === 0"
        :loading="loading"
        :title="`批量停用${resourceLabel || ''}`"
        @click="emit('batch-disable')"
      >
        批量停用
      </el-button>
      <el-button
        v-permission="`${permissionPrefix}:delete`"
        size="small"
        type="danger"
        plain
        :disabled="disabled || selectedCount === 0"
        :loading="loading"
        :title="`批量删除${resourceLabel || ''}`"
        @click="emit('batch-delete')"
      >
        批量删除
      </el-button>
      <el-divider direction="vertical" />
      <el-button
        v-permission="`${permissionPrefix}:create`"
        size="small"
        type="primary"
        plain
        :icon="Upload"
        @click="emit('import')"
      >
        导入
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Upload } from '@element-plus/icons-vue'

const props = defineProps<{
  // 已选条数
  selectedCount: number
  // 资源类型（products / suppliers / customers / warehouses / stores），用于埋点与提示
  resourceType: string
  // 资源中文名，如「商品」
  resourceLabel?: string
  // 批量请求进行中
  loading?: boolean
  // 整体禁用
  disabled?: boolean
}>()

// 资源路径 → 权限码前缀（门店为 base:store）
const PERMISSION_PREFIX: Record<string, string> = {
  products: 'product',
  suppliers: 'supplier',
  customers: 'customer',
  warehouses: 'warehouse',
  stores: 'base:store'
}

const permissionPrefix = computed(() => PERMISSION_PREFIX[props.resourceType] || props.resourceType)

const emit = defineEmits<{
  (e: 'batch-enable'): void
  (e: 'batch-disable'): void
  (e: 'batch-delete'): void
  (e: 'import'): void
}>()
</script>

<style scoped>
.batch-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  margin-bottom: 12px;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.batch-toolbar__info {
  display: flex;
  align-items: center;
  min-height: 24px;
}

.batch-toolbar__hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.batch-toolbar__actions {
  display: flex;
  align-items: center;
}
</style>
