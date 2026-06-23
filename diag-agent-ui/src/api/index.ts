import axios from 'axios'
import type {
  A2aRegistryData,
  A2aStateData,
  EngineType,
  HistoryPageData,
  KnowledgePageData,
  LaneMutationResultData,
  ManagedMemoryMutationData,
  ManagedMemoryVersionData,
  MarkdownTitlesData,
  McpRegistryData,
  McpStateData,
  MemoryMaintenanceRunData,
  MemoryOverviewData,
  MemoryRollbackResultData,
  MemoryVersionDiffData,
  NotificationTestData,
  PromptConfigData,
  RagEvaluationResultData,
  RagGovernanceData,
  RedisOverviewData,
  RuntimeReplayOverviewData,
  RuntimeMetricsData,
  RuntimeAuditExportData,
  RedisWarmupResultData,
  ReplayTimelineData,
  RuntimeStatusData,
  SandboxAuditRecord,
  SandboxConfigData,
  SessionMessage,
  SessionSummary,
  SkillExecutionPlan,
  SkillPageData,
  SystemHealthItem,
  TopologyData,
} from '../types'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 300000,
  headers: {
    'Content-Type': 'application/json',
  },
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      window.location.href = '/login'
    }
    return Promise.reject(error)
  },
)

export interface LoginResponse {
  success: boolean
  message?: string
  data: {
    token: string
    user: {
      id: number
      username: string
      nickname?: string
      role?: string
    }
  }
}

export interface StreamHandle {
  promise: Promise<string>
  abort: () => void
}

export async function loginApi(username: string, password: string): Promise<LoginResponse> {
  const response = await api.post<LoginResponse>('/auth/login', { username, password })
  return response.data
}

export function diagnoseStream(query: string, sessionId: string, onChunk: (chunk: string) => void): StreamHandle {
  const abortController = new AbortController()
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = localStorage.getItem('token')
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  const promise = new Promise<string>((resolve, reject) => {
    fetch(`${API_BASE_URL}/diag/diagnose/stream`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ query, sessionId }),
      signal: abortController.signal,
    })
      .then((response) => {
        if (!response.ok || !response.body) {
          throw new Error(`HTTP error! status: ${response.status}`)
        }

        const reader = response.body.getReader()
        const decoder = new TextDecoder('utf-8')
        let fullText = ''
        let buffer = ''

        const pump = (): void => {
          reader.read().then(({ done, value }) => {
            if (done) {
              onChunk('[DONE]')
              resolve(fullText)
              return
            }

            buffer += decoder.decode(value, { stream: true })
            const lines = buffer.split('\n')
            buffer = lines.pop() ?? ''

            for (const line of lines) {
              if (!line.startsWith('data:')) {
                continue
              }
              const data = line.slice(5).trim()
              if (!data) {
                continue
              }
              if (data === '[DONE]' || data === '[STOPPED]') {
                onChunk(data)
                if (data === '[STOPPED]') {
                  resolve(fullText)
                }
                continue
              }
              fullText += data
              onChunk(data)
            }

            pump()
          }).catch((error: Error) => {
            if (abortController.signal.aborted) {
              reject(new Error('Aborted'))
              return
            }
            reject(error)
          })
        }

        pump()
      })
      .catch((error: Error) => {
        if (abortController.signal.aborted) {
          reject(new Error('Aborted'))
          return
        }
        reject(error)
      })
  })

  return {
    promise,
    abort: () => abortController.abort(),
  }
}

export async function stopGenerateApi(sessionId: string): Promise<void> {
  await api.post(`/diag/stop/${sessionId}`)
}

export async function getHistoryPageApi(pageNum: number, pageSize: number): Promise<HistoryPageData> {
  const response = await api.get<HistoryPageData>('/diag/history/page', { params: { pageNum, pageSize } })
  return response.data
}

export async function deleteHistoryApi(id: number): Promise<void> {
  await api.delete(`/diag/history/${id}`)
}

export async function deleteAllHistoryApi(): Promise<void> {
  await api.delete('/diag/history')
}

export async function saveDiagnosisApi(payload: { query: string; conclusion: string; engine: EngineType; verified: boolean }): Promise<void> {
  await api.post('/diag/save-diagnosis', payload)
}

export async function getSessionsApi(): Promise<SessionSummary[]> {
  const response = await api.get<SessionSummary[]>('/diag/session')
  return response.data
}

export async function createSessionApi(payload: { sessionId: string; title: string; engine: EngineType }): Promise<void> {
  await api.post('/diag/session', payload)
}

export async function updateSessionApi(sessionId: string, payload: Partial<{ title: string; engine: EngineType }>): Promise<void> {
  await api.put(`/diag/session/${sessionId}`, payload)
}

export async function deleteSessionApi(sessionId: string): Promise<void> {
  await api.delete(`/diag/session/${sessionId}`)
}

export async function getSessionMessagesApi(sessionId: string): Promise<SessionMessage[]> {
  const response = await api.get<SessionMessage[]>(`/diag/session/${sessionId}/messages`)
  return response.data
}

export async function clearAnswerCacheApi(): Promise<{ message: string }> {
  const response = await api.delete<{ message: string }>('/diag/cache/answers')
  return response.data
}

export async function getKnowledgeApi(page = 1, size = 10): Promise<KnowledgePageData> {
  const response = await api.get<KnowledgePageData>('/knowledge/list', { params: { page, size } })
  return response.data
}

export async function getKnowledgeContentApi(fileName: string): Promise<string> {
  const response = await api.get<string>(`/knowledge/content/${encodeURIComponent(fileName)}`)
  return response.data
}

export function downloadKnowledgeApi(fileName: string): void {
  const url = `${API_BASE_URL}/knowledge/download/${encodeURIComponent(fileName)}`
  window.open(url, '_blank', 'noopener,noreferrer')
}

export async function uploadKnowledgeApi(file: File): Promise<void> {
  const formData = new FormData()
  formData.append('file', file)
  await api.post('/knowledge/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
}

export async function reloadKnowledgeApi(): Promise<string> {
  const response = await api.post<string>('/knowledge/reload')
  return response.data
}

export async function rebuildKnowledgeApi(): Promise<string> {
  const response = await api.post<string>('/knowledge/rebuild')
  return response.data
}

export async function getSkillsApi(page = 1, size = 10): Promise<SkillPageData> {
  const response = await api.get<SkillPageData>('/skills/list', { params: { page, size } })
  return response.data
}

export async function getSkillContentApi(skillId: string): Promise<string> {
  const response = await api.get<string>(`/skills/content/${encodeURIComponent(skillId)}`)
  return response.data
}

export async function getSkillExecutionPlanApi(skillId: string, payload?: { arguments?: string; context?: string }): Promise<SkillExecutionPlan> {
  const response = await api.get<SkillExecutionPlan>(`/skills/execution/${encodeURIComponent(skillId)}`, {
    params: payload,
  })
  return response.data
}

export function downloadSkillApi(skillId: string): void {
  const url = `${API_BASE_URL}/skills/download/${encodeURIComponent(skillId)}`
  window.open(url, '_blank', 'noopener,noreferrer')
}

export async function uploadSkillApi(file: File): Promise<void> {
  const formData = new FormData()
  formData.append('file', file)
  await api.post('/skills/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
}

export async function getSystemTopologyApi(): Promise<TopologyData> {
  const response = await api.get<TopologyData>('/system/topology')
  return response.data
}

export async function getSystemHealthApi(): Promise<SystemHealthItem[]> {
  const response = await api.get<SystemHealthItem[]>('/system/health')
  return response.data
}

export async function getMarkdownTitlesApi(): Promise<MarkdownTitlesData> {
  const response = await api.get<MarkdownTitlesData>('/system/markdown-titles')
  return response.data
}

export async function getPromptConfigApi(): Promise<PromptConfigData> {
  const response = await api.get<PromptConfigData>('/system/prompt-config')
  return response.data
}

export async function testNotificationApi(): Promise<NotificationTestData> {
  const response = await api.get<NotificationTestData>('/system/test-notification')
  return response.data
}

export async function getRedisOverviewApi(): Promise<RedisOverviewData> {
  const response = await api.get<RedisOverviewData>('/system/redis/overview')
  return response.data
}

export async function warmRedisHotKeysApi(limit = 10): Promise<RedisWarmupResultData> {
  const response = await api.post<RedisWarmupResultData>('/system/redis/warmup', null, {
    params: { limit },
  })
  return response.data
}

export async function getRagGovernanceApi(): Promise<RagGovernanceData> {
  const response = await api.get<RagGovernanceData>('/knowledge/rag/governance')
  return response.data
}

export async function evaluateRagApi(payload: {
  cases: Array<{ query: string; expectedTitles?: string[]; expectedKeywords: string[]; note?: string }>
  label?: string
  baselineSuiteId?: string
  presetId?: string
  configOverride?: {
    vectorTopK?: number
    keywordTopK?: number
    finalTopK?: number
    fusionTopK?: number
    rerankTopN?: number
    minVectorScore?: number
  }
}): Promise<RagEvaluationResultData> {
  const response = await api.post<RagEvaluationResultData>('/knowledge/rag/evaluate', payload)
  return response.data
}

export async function getDiagnosisStatusApi(sessionId: string): Promise<RuntimeStatusData> {
  const response = await api.get<RuntimeStatusData>(`/diag/status/${encodeURIComponent(sessionId)}`)
  return response.data
}

export async function getMemoryOverviewApi(): Promise<MemoryOverviewData> {
  const response = await api.get<MemoryOverviewData>('/memory/overview')
  return response.data
}

export async function runMemoryMaintenanceApi(): Promise<MemoryMaintenanceRunData> {
  const response = await api.post<MemoryMaintenanceRunData>('/memory/maintenance/run')
  return response.data
}

export async function reloadMemoryIndexApi(): Promise<string> {
  const response = await api.post<string>('/memory/reload')
  return response.data
}

export async function getMemoryFileContentApi(path: string): Promise<string> {
  const response = await api.get<string>('/memory/file-content', { params: { path } })
  return response.data
}

export async function getMemoryMutationsApi(limit = 50): Promise<ManagedMemoryMutationData[]> {
  const response = await api.get<ManagedMemoryMutationData[]>('/memory/mutations', { params: { limit } })
  return response.data
}

export async function getMemoryVersionsApi(path: string, limit = 50): Promise<ManagedMemoryVersionData[]> {
  const response = await api.get<ManagedMemoryVersionData[]>('/memory/versions', { params: { path, limit } })
  return response.data
}

export async function getMemoryVersionDiffApi(path: string, fromVersion: number, toVersion: number): Promise<MemoryVersionDiffData> {
  const response = await api.get<MemoryVersionDiffData>('/memory/diff', { params: { path, fromVersion, toVersion } })
  return response.data
}

export async function rollbackMemoryVersionApi(path: string, targetVersion: number, reason = ''): Promise<MemoryRollbackResultData> {
  const response = await api.post<MemoryRollbackResultData>('/memory/rollback', { path, targetVersion, reason })
  return response.data
}

export async function getReplayTimelineApi(sessionId: string, limit = 120): Promise<ReplayTimelineData> {
  const response = await api.get<ReplayTimelineData>(`/runtime/replay/timeline/${encodeURIComponent(sessionId)}`, {
    params: { limit },
  })
  return response.data
}

export async function getReplayTimelineByTaskApi(taskId: string, limit = 120): Promise<ReplayTimelineData> {
  const response = await api.get<ReplayTimelineData>(`/runtime/replay/task/${encodeURIComponent(taskId)}`, {
    params: { limit },
  })
  return response.data
}

export async function getRuntimeReplayOverviewApi(limit = 20): Promise<RuntimeReplayOverviewData> {
  const response = await api.get<RuntimeReplayOverviewData>('/runtime/replay/overview', {
    params: { limit },
  })
  return response.data
}

export async function getRuntimeMetricsApi(limit = 20): Promise<RuntimeMetricsData> {
  const response = await api.get<RuntimeMetricsData>('/runtime/replay/metrics', {
    params: { limit },
  })
  return response.data
}

export async function getRuntimeAuditBySessionApi(sessionId: string, limit = 200): Promise<RuntimeAuditExportData> {
  const response = await api.get<RuntimeAuditExportData>(`/runtime/replay/audit/session/${encodeURIComponent(sessionId)}`, {
    params: { limit },
  })
  return response.data
}

export async function getRuntimeAuditByTaskApi(taskId: string, limit = 200): Promise<RuntimeAuditExportData> {
  const response = await api.get<RuntimeAuditExportData>(`/runtime/replay/audit/task/${encodeURIComponent(taskId)}`, {
    params: { limit },
  })
  return response.data
}

export async function enqueueSessionLaneApi(payload: {
  sessionId: string
  taskId?: string
  laneType: string
  payloadSummary: string
}): Promise<LaneMutationResultData> {
  const response = await api.post<LaneMutationResultData>('/runtime/replay/lane', payload)
  return response.data
}

export async function clearSessionLaneApi(sessionId: string, laneEntryId: string): Promise<LaneMutationResultData> {
  const response = await api.delete<LaneMutationResultData>(
    `/runtime/replay/lane/${encodeURIComponent(sessionId)}/${encodeURIComponent(laneEntryId)}`,
  )
  return response.data
}

export async function getCapabilityManifestApi(): Promise<import('../types').CapabilityManifestData> {
  const response = await api.get<import('../types').CapabilityManifestData>('/protocol/capabilities/manifest')
  return response.data
}

export async function getCapabilityOverviewApi(): Promise<import('../types').CapabilityManifestOverviewData> {
  const response = await api.get<import('../types').CapabilityManifestOverviewData>('/protocol/capabilities/overview')
  return response.data
}

export async function getMcpRegistryApi(): Promise<McpRegistryData> {
  const response = await api.get<McpRegistryData>('/protocol/mcp')
  return response.data
}

export async function getMcpStateApi(): Promise<McpStateData> {
  const response = await api.get<McpStateData>('/protocol/mcp/state')
  return response.data
}

export async function getMcpServerApi(serverId: string): Promise<Record<string, unknown>> {
  const response = await api.get<Record<string, unknown>>(`/protocol/mcp/servers/${encodeURIComponent(serverId)}`)
  return response.data
}

export async function getMcpCapabilityApi(serverId: string): Promise<Record<string, unknown>> {
  const response = await api.get<Record<string, unknown>>(`/protocol/mcp/capabilities/${encodeURIComponent(serverId)}`)
  return response.data
}

export async function getExportableMcpToolsApi(): Promise<Array<Record<string, unknown>>> {
  const response = await api.get<Array<Record<string, unknown>>>('/protocol/mcp/export/tools')
  return response.data
}

export async function getA2aRegistryApi(): Promise<A2aRegistryData> {
  const response = await api.get<A2aRegistryData>('/protocol/a2a')
  return response.data
}

export async function getA2aStateApi(): Promise<A2aStateData> {
  const response = await api.get<A2aStateData>('/protocol/a2a/state')
  return response.data
}

export async function getA2aAgentDetailApi(agentId: string): Promise<Record<string, unknown>> {
  const response = await api.get<Record<string, unknown>>(`/protocol/a2a/agents/${encodeURIComponent(agentId)}`)
  return response.data
}

export async function getA2aRecentEventsApi(limit = 20): Promise<Array<Record<string, unknown>>> {
  const response = await api.get<Array<Record<string, unknown>>>('/a2a/events/recent', {
    params: { limit },
  })
  return response.data
}

export async function getSandboxConfigApi(): Promise<SandboxConfigData> {
  const response = await api.get<SandboxConfigData>('/sandbox/config')
  return response.data
}

export async function getSandboxAuditsApi(): Promise<SandboxAuditRecord[]> {
  const response = await api.get<SandboxAuditRecord[]>('/sandbox/audits')
  return response.data
}

export default api
