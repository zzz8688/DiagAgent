<template>
  <div class="knowledge-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>📚 知识库</span>
          <div class="header-actions">
            <el-button type="success" @click="handleUploadClick" :icon="Upload">
              上传
            </el-button>
            <el-button type="primary" @click="loadKnowledgeList" :icon="Refresh">
              刷新
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="knowledgeList" style="width: 100%" v-loading="loading">
        <el-table-column prop="title" label="标题" />
        <el-table-column prop="type" label="类型" width="100">
          <template #default="scope">
            <el-tag :type="scope.row.type === 'MD' ? 'success' : 'warning'">
              {{ scope.row.type }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="size" label="大小" width="100" />
        <el-table-column prop="updateTime" label="更新时间" width="180" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="scope">
            <el-button
              size="small"
              @click="handleView(scope.row)"
              :icon="View"
            >
              查看
            </el-button>
            <el-button
              size="small"
              type="primary"
              @click="handleDownload(scope.row)"
              :icon="Download"
            >
              下载
            </el-button>
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
      :title="currentDoc.title"
      width="900px"
    >
      <div v-if="currentDoc.type === 'MD'" class="doc-content" v-html="docContent"></div>
      <div v-else class="pdf-container">
        <el-empty description="PDF 文档请点击下载按钮查看"></el-empty>
        <el-button type="primary" @click="handleDownload(currentDoc)" style="margin-top: 20px;">
          下载 PDF 文档
        </el-button>
      </div>

      <template #footer>
        <el-button @click="dialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <input
      ref="fileInputRef"
      type="file"
      style="display: none"
      @change="handleFileChange"
      accept=".md,.pdf"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import { View, Download, Refresh, Upload } from '@element-plus/icons-vue'
import { getKnowledgeApi, getKnowledgeContentApi, downloadKnowledgeApi, uploadKnowledgeApi } from '../api'

const md = new MarkdownIt()

const knowledgeList = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const currentDoc = ref({})
const docContent = ref('')
const fileInputRef = ref(null)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const loadKnowledgeList = async () => {
  loading.value = true
  try {
    const response = await getKnowledgeApi(currentPage.value, pageSize.value)
    knowledgeList.value = response.data.records || []
    total.value = response.data.total || 0
    ElMessage.success(`加载了 ${knowledgeList.value.length} 个文档`)
  } catch (error) {
    ElMessage.error('加载知识库列表失败: ' + error.message)
    console.error(error)
  } finally {
    loading.value = false
  }
}

const handleSizeChange = (val) => {
  pageSize.value = val
  currentPage.value = 1
  loadKnowledgeList()
}

const handleCurrentChange = (val) => {
  currentPage.value = val
  loadKnowledgeList()
}

const handleView = async (row) => {
  currentDoc.value = row

  if (row.type === 'MD') {
    try {
      const response = await getKnowledgeContentApi(row.fileName)
      docContent.value = md.render(response.data || '暂无内容')
    } catch (error) {
      ElMessage.error('加载文档内容失败: ' + error.message)
      docContent.value = '加载失败'
    }
  }

  dialogVisible.value = true
}

const handleDownload = (row) => {
  try {
    downloadKnowledgeApi(row.fileName)
    ElMessage.success(`正在下载：${row.title}`)
  } catch (error) {
    ElMessage.error('下载失败: ' + error.message)
  }
}

const handleUploadClick = () => {
  fileInputRef.value?.click()
}

const handleFileChange = async (event) => {
  const file = event.target.files?.[0]
  if (!file) return

  const validExtensions = ['.md', '.pdf']
  const fileExtension = file.name.substring(file.name.lastIndexOf('.')).toLowerCase()

  if (!validExtensions.includes(fileExtension)) {
    ElMessage.error('只支持 MD 和 PDF 文件')
    return
  }

  try {
    loading.value = true
    await uploadKnowledgeApi(file)
    ElMessage.success('文件上传成功')
    await loadKnowledgeList()
  } catch (error) {
    ElMessage.error('上传失败: ' + error.message)
    console.error(error)
  } finally {
    loading.value = false
    if (fileInputRef.value) {
      fileInputRef.value.value = ''
    }
  }
}

onMounted(() => {
  loadKnowledgeList()
})
</script>

<style scoped>
.knowledge-container {
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

.doc-content {
  max-height: 600px;
  overflow-y: auto;
  line-height: 1.8;
}

.doc-content :deep(h1) {
  color: #303133;
  font-size: 24px;
}

.doc-content :deep(h2) {
  color: #409EFF;
  font-size: 20px;
  margin-top: 20px;
}

.doc-content :deep(h3) {
  color: #67C23A;
  font-size: 16px;
}

.doc-content :deep(ul),
.doc-content :deep(ol) {
  padding-left: 20px;
}

.doc-content :deep(code) {
  background-color: #f5f7fa;
  padding: 2px 6px;
  border-radius: 4px;
  color: #E74C3C;
}

.doc-content :deep(pre) {
  background-color: #282c34;
  color: #abb2bf;
  padding: 15px;
  border-radius: 4px;
  overflow-x: auto;
}

.pdf-container {
  text-align: center;
  padding: 40px;
}
</style>
