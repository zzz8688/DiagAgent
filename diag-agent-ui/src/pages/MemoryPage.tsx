import { useEffect, useMemo, useState } from 'react'
import { getMemoryFileContentApi, getMemoryOverviewApi, reloadMemoryIndexApi, runMemoryMaintenanceApi } from '../api/index.ts'
import type { ManagedMemoryFileData, MemoryOverviewData } from '../types'

const emptyOverview: MemoryOverviewData = {
  summaryCount: 0,
  candidateStatusCounts: {},
  managedFileCount: 0,
  maintenanceRunning: false,
  maintenanceLastRun: {
    trigger: 'idle',
    processedCount: 0,
    promotedCount: 0,
    mergedCount: 0,
    correctedCount: 0,
    forgottenCount: 0,
    rejectedCount: 0,
    failedCount: 0,
    handledCandidateIds: [],
    touchedFiles: false,
    message: 'memory maintenance agent has not run yet',
  },
  compressionRules: {
    summaryEnabled: false,
    summaryType: 'rolling_session_summary',
    maxRecentMessages: 0,
    questionsPerSummary: 0,
    maxTokens: 0,
    autoCompactRecentMessages: 0,
    minimalContextRecentMessages: 0,
    microCompactThreshold: 0,
    autoCompactThreshold: 0,
    minimalContextThreshold: 0,
    approximateCharsPerToken: 0,
  },
  recentActivity: {
    eventCount: 0,
    summaryRefreshCount: 0,
    contextCompactionCount: 0,
    minimalContextActivationCount: 0,
    compactedMessageCount: 0,
    latestSummaryAt: '',
    latestSummaryTopic: '',
    latestSummaryVersion: 0,
    latestCompactionAt: '',
    latestCompactionLevel: '',
    latestMinimalContextMode: false,
    latestUsedBudgetTokens: 0,
    latestMaxBudgetTokens: 0,
    latestUsageRatio: 0,
    latestRetainedMessageCount: 0,
    latestDroppedMessageCount: 0,
  },
  recentEvents: [],
  summaries: [],
  candidates: [],
  managedFiles: [],
  recentMutations: [],
}

function formatTime(value?: string | null) {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function truncate(text?: string, maxLength = 120) {
  if (!text) {
    return '-'
  }
  return text.length > maxLength ? `${text.slice(0, maxLength)}...` : text
}

function readCount(map: Record<string, number>, key: string) {
  return map[key] ?? 0
}

function translateMaintenanceMessage(message?: string) {
  if (!message) {
    return '-'
  }
  if (message === 'memory maintenance run completed') {
    return '已完成本轮候选整理。'
  }
  if (message === 'memory maintenance agent has not run yet') {
    return '还没有执行过候选整理。'
  }
  if (message === '当前没有待处理 memory candidates') {
    return '当前没有待整理候选。按钮不会直接生成新记忆，它只会处理已经生成好的候选。'
  }
  return message
}

export function MemoryPage() {
  const [overview, setOverview] = useState<MemoryOverviewData>(emptyOverview)
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState('')
  const [selectedFile, setSelectedFile] = useState<ManagedMemoryFileData | null>(null)
  const [fileContent, setFileContent] = useState('')

  const pendingCandidateCount = useMemo(() => readCount(overview.candidateStatusCounts, 'PENDING'), [overview.candidateStatusCounts])

  const summaryCards = useMemo(
    () => [
      { label: '滚动摘要', value: overview.summaryCount },
      { label: '待整理候选', value: pendingCandidateCount },
      { label: '已沉淀文件', value: overview.managedFileCount },
      { label: '最近整理', value: formatTime(overview.maintenanceLastRun.finishedAt) },
    ],
    [overview.maintenanceLastRun.finishedAt, overview.managedFileCount, overview.summaryCount, pendingCandidateCount],
  )

  async function loadOverview() {
    setLoading(true)
    try {
      setOverview(await getMemoryOverviewApi())
      setStatus('')
    } catch (error) {
      setStatus(error instanceof Error ? `加载 memory 工作台失败：${error.message}` : '加载 memory 工作台失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadOverview()
  }, [])

  async function handleRunMaintenance() {
    try {
      const result = await runMemoryMaintenanceApi()
      await loadOverview()
      if (result.processedCount === 0) {
        setStatus(
          `当前没有新的长期记忆候选。长期记忆不是点按钮立即生成，而是诊断会话累计到 ${overview.compressionRules.questionsPerSummary || 5} 个用户问题后，系统先自动生成滚动摘要和候选，再由这里整理入库。`,
        )
        return
      }
      setStatus(
        `本轮已整理 ${result.processedCount} 条候选，新增 ${result.promotedCount} 条、合并 ${result.mergedCount} 条、修正 ${result.correctedCount} 条。`,
      )
    } catch (error) {
      setStatus(error instanceof Error ? `执行记忆维护失败：${error.message}` : '执行记忆维护失败')
    }
  }

  async function handleReloadIndex() {
    try {
      const message = await reloadMemoryIndexApi()
      setStatus(message)
      await loadOverview()
    } catch (error) {
      setStatus(error instanceof Error ? `重载 memory 索引失败：${error.message}` : '重载 memory 索引失败')
    }
  }

  async function handleViewFile(file: ManagedMemoryFileData) {
    try {
      setSelectedFile(file)
      setFileContent(await getMemoryFileContentApi(file.path))
    } catch (error) {
      setStatus(error instanceof Error ? `读取 memory file 失败：${error.message}` : '读取 memory file 失败')
      setSelectedFile(null)
      setFileContent('')
    }
  }

  return (
    <div className="page-stack">
      <section className="panel">
        <div className="panel-header split">
          <div>
            <h2>长期记忆</h2>
            <p>这里只看当前长期记忆怎么生成、现在有没有候选，以及已经沉淀了哪些内容。</p>
          </div>
          <div className="toolbar-row">
            <button className="secondary-button" onClick={() => void loadOverview()} disabled={loading}>
              {loading ? '刷新中...' : '刷新'}
            </button>
            <button className="secondary-button" onClick={() => void handleReloadIndex()}>
              重载索引
            </button>
            <button className="primary-button" onClick={() => void handleRunMaintenance()} disabled={overview.maintenanceRunning}>
              {overview.maintenanceRunning ? '整理中' : '整理候选'}
            </button>
          </div>
        </div>
        {status ? <div className="page-banner info">{status}</div> : null}
        <div className="summary-grid">
          {summaryCards.map((item) => (
            <article key={item.label} className="summary-card">
              <span>{item.label}</span>
              <strong>{item.value}</strong>
            </article>
          ))}
        </div>
      </section>

      <section className="panel">
        <div className="panel-header">
          <div>
            <h2>它现在怎么生成</h2>
            <p>长期记忆不是在页面里现点现造，而是沿运行时主链自动产生。</p>
          </div>
        </div>
        <div className="selector-list">
          <div className="status-item">
            <strong>第一步：会话自动生成滚动摘要</strong>
            <span>当前每累计 {overview.compressionRules.questionsPerSummary || 5} 个用户问题，就会刷新一次 rolling summary。</span>
          </div>
          <div className="status-item">
            <strong>第二步：摘要自动变成候选</strong>
            <span>summary 刷新后，系统会自动创建或更新 memory candidate，等待整理入库。</span>
          </div>
          <div className="status-item">
            <strong>第三步：页面按钮只负责整理候选</strong>
            <span>“整理候选”不会现场生成新记忆；它只处理已经存在的待整理候选，并写入长期记忆文件。</span>
          </div>
        </div>
      </section>

      <section className="panel">
        <div className="panel-header">
          <div>
            <h2>当前状态</h2>
            <p>先看当前有没有候选、最近有没有摘要刷新，而不是直接钻到治理细节。</p>
          </div>
        </div>
        <div className="detail-grid">
          <div>
            <strong>摘要开关</strong>
            <p>{overview.compressionRules.summaryEnabled ? '已开启' : '未开启'}</p>
          </div>
          <div>
            <strong>摘要触发阈值</strong>
            <p>{overview.compressionRules.questionsPerSummary || 5} 个用户问题</p>
          </div>
          <div>
            <strong>最近摘要时间</strong>
            <p>{formatTime(overview.recentActivity.latestSummaryAt)}</p>
          </div>
          <div>
            <strong>最近摘要主题</strong>
            <p>{overview.recentActivity.latestSummaryTopic ? truncate(overview.recentActivity.latestSummaryTopic, 72) : '-'}</p>
          </div>
          <div>
            <strong>最近整理时间</strong>
            <p>{formatTime(overview.maintenanceLastRun.finishedAt)}</p>
          </div>
          <div>
            <strong>最近整理结果</strong>
            <p>{translateMaintenanceMessage(overview.maintenanceLastRun.message)}</p>
          </div>
          <div>
            <strong>本轮处理</strong>
            <p>
              {overview.maintenanceLastRun.processedCount} 条，新增 {overview.maintenanceLastRun.promotedCount} 条
            </p>
          </div>
          <div>
            <strong>已写入文件</strong>
            <p>{overview.maintenanceLastRun.touchedFiles ? '是' : '否'}</p>
          </div>
        </div>
      </section>

      <section className="panel">
        <div className="panel-header">
          <div>
            <h2>最近滚动摘要</h2>
            <p>这里是系统自动生成的会话摘要，也是长期记忆候选的上游来源。</p>
          </div>
        </div>
        <div className="selector-list">
          {overview.recentEvents.length > 0 ? (
            overview.summaries.slice(0, 6).map((summary, index) => (
              <div key={`${summary.memoryId}-${summary.version}-${index}`} className="status-item">
                <strong>{truncate(summary.topic as string, 64)}</strong>
                <span>memoryId：{truncate(summary.memoryId as string, 32)}</span>
                <span>版本：v{summary.version ?? 0}</span>
                <span>更新时间：{formatTime(summary.updatedAt as string)}</span>
                <span>{truncate(summary.content as string, 180)}</span>
              </div>
            ))
          ) : (
            <div className="empty-state compact">最近还没有生成新的滚动摘要。</div>
          )}
        </div>
      </section>

      <section className="panel">
        <div className="panel-header">
          <div>
            <h2>待整理候选</h2>
            <p>如果这里是空的，点击“整理候选”也不会新增长期记忆。</p>
          </div>
        </div>
        <div className="selector-list">
          {overview.candidates.length > 0 ? (
            overview.candidates.slice(0, 8).map((candidate, index) => (
              <div key={`${candidate.id}-${index}`} className="status-item">
                <strong>{truncate(candidate.topic as string, 64)}</strong>
                <span>状态：{candidate.status}</span>
                <span>操作：{candidate.proposedOperation || '-'}</span>
                <span>目标文件：{candidate.targetFilePath || '-'}</span>
                <span>{truncate(candidate.summaryContent as string, 180)}</span>
              </div>
            ))
          ) : (
            <div className="empty-state compact">
              当前没有待整理候选。原因通常不是按钮失效，而是当前诊断会话还没有累计到新的 rolling summary，或者候选已经被前一轮整理掉了。
            </div>
          )}
        </div>
      </section>

      <section className="panel">
        <div className="panel-header">
          <div>
            <h2>长期记忆文件</h2>
            <p>这里只保留已经真正沉淀下来的长期记忆内容。</p>
          </div>
        </div>
        <div className="selector-list">
          {overview.managedFiles.length > 0 ? (
            overview.managedFiles.slice(0, 8).map((file) => (
              <div key={file.path} className="status-item">
                <strong>{file.fileName}</strong>
                <span>路径：{file.path}</span>
                <span>主题：{truncate(file.topic, 72)}</span>
                <span>状态：{file.status || '-'}</span>
                <span>最近操作：{file.lastOperation || '-'}</span>
                <span>更新时间：{file.updateTime || '-'}</span>
                <span>版本：v{file.latestVersion ?? 0}</span>
                <button className="link-button" onClick={() => void handleViewFile(file)}>
                  查看文件
                </button>
              </div>
            ))
          ) : (
            <div className="empty-state compact">目前还没有真正沉淀下来的长期记忆文件。</div>
          )}
        </div>
      </section>

      {selectedFile ? (
        <div className="modal-overlay" onClick={() => setSelectedFile(null)}>
          <div className="modal-card modal-wide" onClick={(event) => event.stopPropagation()}>
            <div className="panel-header split">
              <div>
                <h2>{selectedFile.fileName}</h2>
                <p>{selectedFile.path}</p>
              </div>
              <button className="ghost-button" onClick={() => setSelectedFile(null)}>
                关闭
              </button>
            </div>
            <pre className="json-block">{fileContent}</pre>
          </div>
        </div>
      ) : null}
    </div>
  )
}
