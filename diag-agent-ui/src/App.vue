<template>
  <el-container class="layout-container">
    <el-aside width="200px" class="sidebar">
      <div class="logo">
        <el-icon :size="24"><Monitor /></el-icon>
        <span>DiagAgent</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409EFF"
        router
      >
        <el-menu-item index="/">
          <el-icon><Monitor /></el-icon>
          <span>智能诊断</span>
        </el-menu-item>
        <el-menu-item index="/history">
          <el-icon><Star /></el-icon>
          <span>收藏夹</span>
        </el-menu-item>
        <el-menu-item index="/knowledge">
          <el-icon><Reading /></el-icon>
          <span>知识库</span>
        </el-menu-item>
        <el-menu-item index="/system">
          <el-icon><DataLine /></el-icon>
          <span>系统状态</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <h2>{{ currentTitle }}</h2>
        <div class="user-info">
          <el-tag type="success">在线</el-tag>
        </div>
      </el-header>

      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Monitor, Star, Reading, DataLine } from '@element-plus/icons-vue'

const route = useRoute()

const activeMenu = computed(() => route.path)
const currentTitle = computed(() => route.meta.title || 'DiagAgent')
</script>

<style scoped>
.layout-container {
  height: 100vh;
}

.sidebar {
  background-color: #304156;
  color: #fff;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #fff;
  font-size: 20px;
  font-weight: bold;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.el-menu {
  border-right: none;
}

.header {
  background-color: #fff;
  border-bottom: 1px solid #e6e6e6;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}

.header h2 {
  margin: 0;
  font-size: 18px;
  color: #303133;
}

.main-content {
  background-color: #f0f2f5;
  padding: 20px;
}
</style>
