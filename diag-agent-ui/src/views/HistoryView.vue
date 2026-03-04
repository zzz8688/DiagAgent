<template>
  <div class="history-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>⭐ 收藏夹</span>
          <div class="header-actions">
            <el-button @click="loadHistory" :icon="Refresh">
              刷新
            </el-button>
            <el-button @click="handleDeleteAll" :icon="Close" v-if="total > 0">
              全部取消收藏
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="historyList" style="width: 100%" v-loading="loading">
        <el-table-column prop="query" label="查询内容" width="300" />
        <el-table-column prop="conclusion" label="诊断结论">
          <template #default="scope">
            {{ truncateText(scope.row.conclusion, 100) }}
          </template>
        </el-table-column>
        <el-table-column prop="engine" label="引擎" width="100" />
        <el-table-column label="时间" width="180">
          <template #default="scope">
            {{ formatTime(scope.row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="scope">
            <div style="display: flex; gap: 8px;">
              <el-button
                size="small"
                @click="handleView(scope.row)"
                :icon="View"
              >
                查看
              </el-button>
              <el-button
                size="small"
                @click="handleDelete(scope.row)"
                :icon="Close"
              >
                取消收藏
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[5, 10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        :total="total"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
        style="margin-top: 20px; justify-content: flex-end;"
      />
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      title="诊断详情"
      width="800px"
    >
      <el-descriptions :column="2" border>
        <el-descriptions-item label="查询内容">
          {{ currentRecord.query }}
        </el-descriptions-item>
        <el-descriptions-item label="诊断引擎">
          {{ currentRecord.engine }}
        </el-descriptions-item>
        <el-descriptions-item label="诊断时间" :span="2">
          {{ currentRecord.createdAt }}
        </el-descriptions-item>
        <el-descriptions-item label="诊断结论" :span="2">
          <pre style="white-space: pre-wrap; word-wrap: break-word;">{{ currentRecord.conclusion }}</pre>
        </el-descriptions-item>
      </el-descriptions>

      <template #footer>
        <el-button @click="dialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="handleCopy">复制</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, View, Close } from '@element-plus/icons-vue'
import { getHistoryPageApi, deleteHistoryApi, deleteAllHistoryApi } from '../api'

const loading = ref(false)
const dialogVisible = ref(false)
const currentRecord = ref({})

const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const historyList = ref([])

const truncateText = (text, maxLength) => {
  if (!text) return ''
  if (text.length <= maxLength) return text
  return text.substring(0, maxLength) + '...'
}

const formatTime = (time) => {
  if (!time) return ''
  return time.replace('T', ' ')
}

const handleView = (row) => {
  currentRecord.value = row
  dialogVisible.value = true
}

const handleDelete = (row) => {
  ElMessageBox.confirm('确定要取消收藏这条记录吗？', '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await deleteHistoryApi(row.id)
      ElMessage.success('已取消收藏')
      loadHistory()
    } catch (error) {
      ElMessage.error('取消收藏失败')
    }
  }).catch(() => {})
}

const handleDeleteAll = () => {
  ElMessageBox.confirm('确定要全部取消收藏吗？此操作不可恢复！', '警告', {
    confirmButtonText: '确定取消',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await deleteAllHistoryApi()
      ElMessage.success('全部取消收藏成功')
      loadHistory()
    } catch (error) {
      ElMessage.error('取消收藏失败')
    }
  }).catch(() => {})
}

const handleCopy = () => {
  const text = `
查询：${currentRecord.value.query}
结论：${currentRecord.value.conclusion}
  `.trim()
  navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}

const loadHistory = async () => {
  loading.value = true
  try {
    const res = await getHistoryPageApi(currentPage.value, pageSize.value)
    historyList.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (error) {
    console.error('加载历史记录失败:', error)
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

const handleSizeChange = (val) => {
  pageSize.value = val
  currentPage.value = 1
  loadHistory()
}

const handleCurrentChange = (val) => {
  currentPage.value = val
  loadHistory()
}

onMounted(() => {
  loadHistory()
})
</script>

<style scoped>
.history-container {
  max-width: 1400px;
  margin: 0 auto;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-actions {
  display: flex;
  gap: 10px;
}

pre {
  margin: 0;
  font-family: inherit;
  white-space: pre-wrap;
  word-wrap: break-word;
}
</style>
