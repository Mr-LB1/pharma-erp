<template>
  <!-- 全量组件注册已改为按需引入（决策 8）；中文语言包改由 el-config-provider 提供 -->
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<style>
html,
body,
#app {
  height: 100%;
  margin: 0;
  padding: 0;
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
}

/* 桌面端最低 1280 宽（交互 9）：更窄时整体横向滚动，不做移动端适配，
   避免表格/工具栏被挤成换行或溢出视口 */
body {
  min-width: 1280px;
  overflow-x: auto;
  -webkit-font-smoothing: antialiased;
}
</style>
