<template>
  <div class="system-container">
    <el-card class="health-card">
      <template #header>
        <div class="card-header">
          <span>🏥 服务健康状态</span>
          <el-button type="primary" @click="loadHealthData" :icon="Refresh" :loading="healthLoading">
            刷新
          </el-button>
        </div>
      </template>

      <el-row :gutter="20">
        <el-col :span="6" v-for="service in healthList" :key="service.id">
          <el-card class="service-card" :class="getStatusClass(service.status)">
            <div class="service-name">{{ service.name }}</div>
            <el-tag :type="getStatusTagType(service.status)" size="large">
              {{ service.status }}
            </el-tag>
            <div class="metrics">
              <div class="metric-item">
                <span class="metric-label">CPU</span>
                <el-progress :percentage="service.cpu" :color="getProgressColor(service.cpu)" :stroke-width="10" />
              </div>
              <div class="metric-item">
                <span class="metric-label">内存</span>
                <el-progress :percentage="service.memory" :color="getProgressColor(service.memory)" :stroke-width="10" />
              </div>
              <div class="metric-item">
                <span class="metric-label">延迟</span>
                <span class="metric-value">{{ service.latency }}ms</span>
              </div>
              <div class="metric-item">
                <span class="metric-label">错误率</span>
                <span class="metric-value" :class="getErrorClass(service.errorRate)">{{ service.errorRate }}%</span>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </el-card>

    <el-card class="topology-card">
      <template #header>
        <div class="card-header">
          <span>🌐 系统拓扑图</span>
          <el-button type="primary" @click="loadTopologyData" :icon="Refresh" :loading="topologyLoading">
            刷新
          </el-button>
        </div>
      </template>

      <div class="topology-container" ref="topologyRef">
        <svg :width="svgWidth" :height="svgHeight">
          <defs>
            <marker id="arrowhead" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
              <polygon points="0 0, 10 3.5, 0 7" fill="#909399" />
            </marker>
          </defs>

          <line
            v-for="link in topologyData.links"
            :key="link.source + '-' + link.target"
            :x1="getNodeX(link.source)"
            :y1="getNodeY(link.source)"
            :x2="getNodeX(link.target)"
            :y2="getNodeY(link.target)"
            stroke="#909399"
            stroke-width="2"
            marker-end="url(#arrowhead)"
          />

          <g v-for="node in topologyData.nodes" :key="node.id" class="node">
            <circle
              :cx="node.x"
              :cy="node.y"
              :r="50"
              :fill="getNodeColor(node.status)"
              stroke="#fff"
              stroke-width="3"
            />
            <text
              :x="node.x"
              :y="node.y + 5"
              text-anchor="middle"
              fill="#fff"
              font-size="12"
              font-weight="bold"
            >
              {{ node.name.split(' ')[0] }}
            </text>
            <text
              v-if="node.name.split(' ')[1]"
              :x="node.x"
              :y="node.y + 22"
              text-anchor="middle"
              fill="#fff"
              font-size="10"
            >
              {{ node.name.split(' ')[1] }}
            </text>
          </g>
        </svg>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { getSystemTopologyApi, getSystemHealthApi } from '../api'

const healthList = ref([])
const topologyData = ref({ nodes: [], links: [] })
const healthLoading = ref(false)
const topologyLoading = ref(false)
const topologyRef = ref(null)
const svgWidth = 800
const svgHeight = 650

const getStatusClass = (status) => {
  const map = {
    'OK': 'status-ok',
    'Warning': 'status-warning',
    'Critical': 'status-critical'
  }
  return map[status] || 'status-ok'
}

const getStatusTagType = (status) => {
  const map = {
    'OK': 'success',
    'Warning': 'warning',
    'Critical': 'danger'
  }
  return map[status] || 'info'
}

const getProgressColor = (value) => {
  if (value >= 90) return '#f56c6c'
  if (value >= 70) return '#e6a23c'
  return '#67c23a'
}

const getErrorClass = (value) => {
  if (value >= 40) return 'error-high'
  if (value >= 10) return 'error-medium'
  return ''
}

const getNodeColor = (status) => {
  const map = {
    'OK': '#67c23a',
    'Warning': '#e6a23c',
    'Critical': '#f56c6c'
  }
  return map[status] || '#909399'
}

const getNodeX = (nodeId) => {
  const node = topologyData.value.nodes.find(n => n.id === nodeId)
  return node ? node.x : 0
}

const getNodeY = (nodeId) => {
  const node = topologyData.value.nodes.find(n => n.id === nodeId)
  return node ? node.y : 0
}

const loadHealthData = async () => {
  healthLoading.value = true
  try {
    const response = await getSystemHealthApi()
    healthList.value = response.data
  } catch (error) {
    ElMessage.error('加载健康状态失败: ' + error.message)
    console.error(error)
  } finally {
    healthLoading.value = false
  }
}

const loadTopologyData = async () => {
  topologyLoading.value = true
  try {
    const response = await getSystemTopologyApi()
    topologyData.value = response.data
  } catch (error) {
    ElMessage.error('加载拓扑图失败: ' + error.message)
    console.error(error)
  } finally {
    topologyLoading.value = false
  }
}

onMounted(() => {
  loadHealthData()
  loadTopologyData()
})
</script>

<style scoped>
.system-container {
  max-width: 1600px;
  margin: 0 auto;
}

.health-card,
.topology-card {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.service-card {
  margin-bottom: 20px;
  text-align: center;
  transition: all 0.3s;
}

.service-card:hover {
  transform: translateY(-5px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.service-card.status-ok {
  border-left: 4px solid #67c23a;
}

.service-card.status-warning {
  border-left: 4px solid #e6a23c;
}

.service-card.status-critical {
  border-left: 4px solid #f56c6c;
}

.service-name {
  font-size: 16px;
  font-weight: bold;
  margin-bottom: 10px;
  color: #303133;
}

.metrics {
  margin-top: 15px;
}

.metric-item {
  margin-bottom: 12px;
  text-align: left;
}

.metric-label {
  display: inline-block;
  width: 50px;
  font-size: 12px;
  color: #909399;
  margin-right: 10px;
}

.metric-value {
  font-size: 14px;
  font-weight: bold;
  color: #303133;
}

.error-high {
  color: #f56c6c !important;
}

.error-medium {
  color: #e6a23c !important;
}

.topology-container {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 20px;
  background-color: #f5f7fa;
  border-radius: 4px;
}

.node {
  cursor: pointer;
  transition: all 0.3s;
}

.node:hover circle {
  filter: brightness(1.1);
  transform: scale(1.05);
  transform-origin: center;
}
</style>
