<template>
  <div class="doc-batch-bar">
    <div class="doc-batch-bar__info">
      <el-tag v-if="selectedCount > 0" type="primary" effect="dark" round size="small">
        已选 {{ selectedCount }} 项
      </el-tag>
      <span v-else class="doc-batch-bar__hint">勾选列表左侧复选框可批量操作</span>
    </div>
    <div class="doc-batch-bar__actions">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  /** 已选条数 */
  selectedCount: number
}>()
</script>

<style scoped>
.doc-batch-bar {
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

.doc-batch-bar__info {
  display: flex;
  align-items: center;
  min-height: 24px;
}

.doc-batch-bar__hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.doc-batch-bar__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
