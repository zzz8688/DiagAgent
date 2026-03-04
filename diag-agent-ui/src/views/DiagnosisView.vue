<template>
  <div class="diagnosis-container">
    <el-row :gutter="20">
      <el-col :span="selectedEngine === 'orchestrator' ? 24 : 18">
        <el-card class="input-card">
          <template #header>
            <div class="card-header">
              <span>🔍 智能诊断</span>
              <div class="engine-selector">
                <label>诊断引擎：</label>
                <el-select v-model="selectedEngine" @change="handleEngineChange" style="width: 200px;">
                  <el-option label="LLM Agent (智能推理)" value="llm" />
                  <el-option label="Orchestrator (确定性流程)" value="orchestrator" />
                </el-select>
                <el-tag :type="selectedEngine === 'llm' ? 'primary' : 'success'" style="margin-left: 10px;">
                  {{ selectedEngine === 'llm' ? 'LLM Agent' : 'Orchestrator' }}
                </el-tag>
              </div>
            </div>
          </template>

          <el-form @submit.prevent="handleDiagnose">
            <el-form-item>
              <el-input
                v-model="query"
                type="textarea"
                :rows="3"
                placeholder="请描述您遇到的问题，例如：结账失败了、商品加载很慢..."
                :disabled="isLoading"
                @keyup.enter.ctrl="handleDiagnose"
              />
            </el-form-item>
            <el-form-item>
              <el-button
                type="primary"
                :loading="isLoading"
                @click="handleDiagnose"
                :icon="isLoading ? 'Loading' : 'Promotion'"
              >
                {{ isLoading ? '诊断中...' : '开始诊断' }}
              </el-button>
              <el-button @click="handleNewConversation" :disabled="isLoading" v-if="selectedEngine === 'llm'">
                新会话
              </el-button>
              <el-button @click="handleClear" :disabled="isLoading">
                清空
              </el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card v-if="selectedEngine === 'llm' && messages.length > 0" class="chat-card">
          <div class="chat-messages">
            <div
              v-for="(msg, index) in messages"
              :key="index"
              :class="['message', msg.role]"
            >
              <div class="message-role">
                {{ msg.role === 'user' ? '👤 您' : '🤖 AI' }}
                <el-button
                  v-if="msg.role === 'assistant'"
                  size="small"
                  :type="favoritedIndexes.has(index) ? 'info' : 'primary'"
                  text
                  @click="handleFavorite(index)"
                  style="margin-left: 10px;"
                >
                  {{ favoritedIndexes.has(index) ? '⭐ 已收藏' : '⭐ 收藏' }}
                </el-button>
              </div>
              <div class="message-content" v-html="formatMessage(msg.content)"></div>
            </div>
          </div>
        </el-card>

        <el-card v-if="selectedEngine === 'orchestrator' && result" class="result-card">
          <template #header>
            <div class="card-header">
              <span>📊 诊断结果</span>
              <div style="display: flex; align-items: center; gap: 10px;">
                <el-tag :type="result.includes('置信度') ? 'success' : 'warning'">
                  {{ result.includes('置信度') ? '高置信度' : '需要人工介入' }}
                </el-tag>
                <el-button
                  size="small"
                  :type="orchestratorFavorited ? 'info' : 'primary'"
                  text
                  @click="handleOrchestratorFavorite"
                >
                  {{ orchestratorFavorited ? '⭐ 已收藏' : '⭐ 收藏' }}
                </el-button>
              </div>
            </div>
          </template>
          <div class="result-content" v-html="formattedResult"></div>
        </el-card>

        <el-card v-if="quickQuestions.length > 0" class="quick-card">
          <template #header>
            <span>💡 常见问题</span>
          </template>
          <div class="quick-questions">
            <el-tag
              v-for="(question, index) in quickQuestions"
              :key="index"
              class="quick-tag"
              @click="handleQuickQuestion(question)"
            >
              {{ question }}
            </el-tag>
          </div>
        </el-card>
      </el-col>

      <el-col :span="6" v-if="selectedEngine === 'llm'">
        <el-card class="session-card">
          <template #header>
            <div class="card-header">
              <span>📋 会话列表</span>
              <el-button size="small" @click="loadSessions" :icon="Refresh">刷新</el-button>
            </div>
          </template>
          <div class="session-list">
            <div
              v-for="session in sessions"
              :key="session.id"
              :class="['session-item', { active: session.sessionId === sessionId }]"
              @click="selectSession(session)"
            >
              <div class="session-title">{{ session.title || '未命名会话' }}</div>
              <div class="session-meta">
                <el-tag size="small">{{ session.engine === 'llm' ? 'LLM' : 'Orch' }}</el-tag>
                <span class="session-time">{{ formatTime(session.updatedAt) }}</span>
                <el-button size="small" type="danger" text @click.stop="deleteSession(session.sessionId)">删除</el-button>
              </div>
            </div>
            <el-empty v-if="sessions.length === 0" description="暂无保存的会话" :image-size="60" />
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { diagnoseApi, getSessionsApi, createSessionApi, updateSessionApi, deleteSessionApi } from '../api'
import MarkdownIt from 'markdown-it'
import axios from 'axios'

const API_BASE_URL = 'http://localhost:8080/api'

const md = new MarkdownIt()

const query = ref('')
const result = ref('')
const isLoading = ref(false)
const selectedEngine = ref('llm')
const sessionId = ref('')
const messages = ref([])
const sessions = ref([])
const favoritedIndexes = ref(new Set())
const orchestratorFavorited = ref(false)
const lastOrchestratorQuery = ref('')

const quickQuestions = ref([
  '结账失败了',
  '商品加载很慢',
  '系统健康吗',
  '支付网关超时',
  '数据库连接超时'
])

const formattedResult = computed(() => {
  return md.render(result.value)
})

const generateSessionId = () => {
  return 'session-' + Date.now() + '-' + Math.random().toString(36).substr(2, 9)
}

const formatMessage = (content) => {
  return md.render(content)
}

const formatTime = (timeStr) => {
  if (!timeStr) return ''
  const date = new Date(timeStr)
  return date.toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

const loadSessions = async () => {
  try {
    const res = await getSessionsApi()
    sessions.value = res.data || []
  } catch (error) {
    console.error('加载会话失败:', error)
  }
}

const selectSession = async (session) => {
  sessionId.value = session.sessionId
  selectedEngine.value = session.engine || 'llm'
  result.value = ''
  favoritedIndexes.value = new Set()
  try {
    const res = await axios.get(`${API_BASE_URL}/agent/session/${session.sessionId}/messages`)
    messages.value = res.data.map(msg => ({
      role: msg.type === 'USER' ? 'user' : 'assistant',
      content: msg.text
    }))
    ElMessage.success('已加载会话: ' + (session.title || '未命名'))
  } catch (error) {
    console.error('加载会话消息失败:', error)
    ElMessage.error('加载会话消息失败')
    messages.value = []
  }
}

const deleteSession = async (sid) => {
  try {
    await deleteSessionApi(sid)
    ElMessage.success('删除成功')
    loadSessions()
    if (sessionId.value === sid) {
      handleNewConversation()
    }
  } catch (error) {
    ElMessage.error('删除失败')
  }
}

const handleNewConversation = () => {
  sessionId.value = generateSessionId()
  messages.value = []
  result.value = ''
}

const autoSaveSession = async () => {
  const title = messages.value.length > 0 ? 
    messages.value[0].content.substring(0, 30) + (messages.value[0].content.length > 30 ? '...' : '') : 
    '新会话'

  try {
    const existingSession = sessions.value.find(s => s.sessionId === sessionId.value)
    if (existingSession) {
      await updateSessionApi(sessionId.value, { title, engine: selectedEngine.value })
    } else {
      await createSessionApi({ sessionId: sessionId.value, title, engine: selectedEngine.value })
    }
    loadSessions()
  } catch (error) {
    console.error('自动保存会话失败:', error)
  }
}

const handleFavorite = async (index) => {
  const userMsg = messages.value[index - 1]
  const aiMsg = messages.value[index]
  
  if (!userMsg || !aiMsg || userMsg.role !== 'user' || aiMsg.role !== 'assistant') {
    ElMessage.error('无法收藏此消息')
    return
  }

  if (favoritedIndexes.value.has(index)) {
    ElMessage.info('此问答已收藏')
    return
  }

  try {
    const diagnosisData = {
      query: userMsg.content,
      conclusion: aiMsg.content,
      engine: selectedEngine.value,
      verified: false
    }
    await axios.post(`${API_BASE_URL}/agent/save-diagnosis`, diagnosisData)
    favoritedIndexes.value.add(index)
    ElMessage.success('已添加到收藏夹')
  } catch (error) {
    console.error('收藏失败:', error)
    ElMessage.error('收藏失败')
  }
}

const handleOrchestratorFavorite = async () => {
  if (!lastOrchestratorQuery.value || !result.value) {
    ElMessage.error('没有可收藏的诊断结果')
    return
  }

  if (orchestratorFavorited.value) {
    ElMessage.info('此诊断结果已收藏')
    return
  }

  try {
    const diagnosisData = {
      query: lastOrchestratorQuery.value,
      conclusion: result.value,
      engine: selectedEngine.value,
      verified: false
    }
    await axios.post(`${API_BASE_URL}/agent/save-diagnosis`, diagnosisData)
    orchestratorFavorited.value = true
    ElMessage.success('已添加到收藏夹')
  } catch (error) {
    console.error('收藏失败:', error)
    ElMessage.error('收藏失败')
  }
}

const handleSaveSession = async () => {
  const title = messages.value.length > 0 ? 
    messages.value[0].content.substring(0, 30) + (messages.value[0].content.length > 30 ? '...' : '') : 
    '新会话'

  try {
    const existingSession = sessions.value.find(s => s.sessionId === sessionId.value)
    if (existingSession) {
      await updateSessionApi(sessionId.value, { title, engine: selectedEngine.value })
    } else {
      await createSessionApi({ sessionId: sessionId.value, title, engine: selectedEngine.value })
    }
    ElMessage.success('会话已保存')
    loadSessions()
  } catch (error) {
    console.error('保存会话失败:', error)
    ElMessage.error('保存失败')
  }
}

const handleDiagnose = async () => {
  if (!query.value.trim()) {
    ElMessage.warning('请输入问题描述')
    return
  }

  isLoading.value = true

  if (selectedEngine.value === 'llm') {
    const userQuery = query.value
    messages.value.push({
      role: 'user',
      content: userQuery
    })
    messages.value.push({
      role: 'assistant',
      content: ''
    })
    const assistantIndex = messages.value.length - 1

    try {
      const response = await axios.post(`${API_BASE_URL}/agent/diagnose/non-streaming`, {
        query: userQuery,
        sessionId: sessionId.value
      })
      messages.value[assistantIndex].content = response.data
      await autoSaveSession()
      ElMessage.success('诊断完成')
    } catch (error) {
      ElMessage.error('诊断失败：' + error.message)
    } finally {
      isLoading.value = false
      query.value = ''
    }
  } else {
    try {
      const response = await axios.post(`${API_BASE_URL}/diagnose/orchestrated`, {
        query: query.value
      })

      if (response.data.conclusion) {
        result.value = response.data.conclusion
      } else {
        result.value = JSON.stringify(response.data, null, 2)
      }

      if (response.data.confidence !== undefined) {
        result.value += '\n\n【置信度】' + (response.data.confidence * 100).toFixed(0) + '%'
      }

      lastOrchestratorQuery.value = query.value
      orchestratorFavorited.value = false
      ElMessage.success('诊断完成，点击 ⭐收藏 可以保存到收藏夹')
    } catch (error) {
      ElMessage.error('诊断失败：' + error.message)
    } finally {
      isLoading.value = false
    }
  }
}

const saveDiagnosisToBackend = async (q, res) => {
  let actualContent = res
  if (typeof res === 'string' && res.startsWith('data:')) {
    actualContent = res.substring(5).trim()
  }

  const diagnosisData = {
    query: q,
    conclusion: actualContent,
    engine: selectedEngine.value,
    verified: false
  }

  try {
    await axios.post(`${API_BASE_URL}/agent/save-diagnosis`, diagnosisData)
  } catch (error) {
    console.error('保存诊断记录失败:', error)
  }
}

const handleClear = () => {
  query.value = ''
  result.value = ''
  messages.value = []
  orchestratorFavorited.value = false
  lastOrchestratorQuery.value = ''
}

const handleQuickQuestion = (question) => {
  query.value = question
  handleDiagnose()
}

const handleEngineChange = () => {
  ElMessage.info(`已切换到 ${selectedEngine.value === 'llm' ? 'LLM Agent' : 'Orchestrator'} 模式`)
  messages.value = []
  result.value = ''
  orchestratorFavorited.value = false
  lastOrchestratorQuery.value = ''
}

onMounted(() => {
  sessionId.value = generateSessionId()
  loadSessions()
})
</script>

<style scoped>
.diagnosis-container {
  max-width: 1400px;
  margin: 0 auto;
}

.input-card,
.chat-card,
.quick-card,
.session-card,
.result-card {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.engine-selector {
  display: flex;
  align-items: center;
  gap: 10px;
}

.engine-selector label {
  font-weight: 500;
}

.chat-messages {
  max-height: 500px;
  overflow-y: auto;
}

.message {
  margin-bottom: 20px;
  padding: 15px;
  border-radius: 8px;
}

.message.user {
  background-color: #e6f7ff;
  margin-left: 50px;
}

.message.assistant {
  background-color: #f6ffed;
  margin-right: 50px;
}

.message-role {
  font-weight: bold;
  margin-bottom: 8px;
  color: #666;
}

.message-content,
.result-content {
  line-height: 1.8;
}

.message-content :deep(h3),
.result-content :deep(h3) {
  color: #303133;
  margin-top: 15px;
}

.message-content :deep(ul),
.result-content :deep(ul) {
  padding-left: 20px;
}

.quick-questions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.quick-tag {
  cursor: pointer;
  transition: all 0.3s;
}

.quick-tag:hover {
  transform: translateY(-2px);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.session-list {
  max-height: 400px;
  overflow-y: auto;
}

.session-item {
  padding: 10px;
  border-bottom: 1px solid #eee;
  cursor: pointer;
  transition: background 0.2s;
}

.session-item:hover {
  background-color: #f5f7fa;
}

.session-item.active {
  background-color: #e6f7ff;
}

.session-title {
  font-weight: 500;
  margin-bottom: 5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.session-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #999;
}

.session-time {
  flex: 1;
}
</style>
