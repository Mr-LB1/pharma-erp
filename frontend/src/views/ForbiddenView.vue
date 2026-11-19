<template>
  <el-card shadow="never" class="page-card">
    <el-result icon="warning" title="无权访问" sub-title="当前账号没有访问该页面的权限，请联系管理员分配角色。">
      <template #extra>
        <el-button type="primary" @click="goHome">返回首页</el-button>
      </template>
    </el-result>
  </el-card>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { firstAccessiblePath } from '@/layout/menus'

const router = useRouter()
const authStore = useAuthStore()

function goHome() {
  router.replace(firstAccessiblePath(authStore.hasPermission))
}
</script>

<style scoped>
.page-card {
  border-radius: 8px;
}
</style>
