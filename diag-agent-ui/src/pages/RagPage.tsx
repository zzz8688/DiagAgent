import { useEffect, useMemo, useState } from 'react'
import { evaluateRagApi, getRagGovernanceApi, rebuildKnowledgeApi } from '../api/index.ts'
import type { RagBaselineSuiteData, RagConfigPresetData, RagGovernanceData } from '../types'

const FULL_SUITE_ID = 'frontend-diagnosis-full'
const SAMPLE_1_SUITE_ID = 'frontend-diagnosis-sample-1'
const SAMPLE_2_SUITE_ID = 'frontend-diagnosis-sample-2'

const FRONTEND_DIAGNOSIS_SAMPLES: Record<
  string,
  {
    displayName: string
    cases: Array<{ query: string; expectedTitles?: string[]; expectedKeywords: string[]; note: string }>
  }
> = {
  [FULL_SUITE_ID]: {
    displayName: '前端诊断全量基线',
    cases: [],
  },
  [SAMPLE_1_SUITE_ID]: {
    displayName: '前端页面样例 1',
    cases: [
      {
        query: '结账失败了',
        expectedTitles: ['支付网关抖动', '支付网关超时故障'],
        expectedKeywords: ['支付', '网关', '504'],
        note: '默认问题 1；应能召回支付网关超时、上游抖动和重试耗尽相关知识。',
      },
      {
        query: '支付一直转圈',
        expectedTitles: ['支付网关抖动'],
        expectedKeywords: ['支付', '重试', '转圈'],
        note: '默认问题 2；应能召回支付轮询等待、网关回执延迟和重试控制相关知识。',
      },
      {
        query: '商品页面打开很慢',
        expectedTitles: ['商品页慢但非数据库'],
        expectedKeywords: ['商品', '首屏', '缓存'],
        note: '默认问题 3；应能召回商品页首屏阻塞、缓存未命中和下游依赖慢相关知识。',
      },
    ],
  },
  [SAMPLE_2_SUITE_ID]: {
    displayName: '前端页面样例 2',
    cases: [
      {
        query: '系统好像有点异常',
        expectedTitles: ['健康巡检型异常', '服务健康检查'],
        expectedKeywords: ['健康', '巡检', '实例'],
        note: '默认问题 4；应能召回健康巡检、实例异常和服务健康评分相关知识。',
      },
      {
        query: '页面提示数据库连接超时',
        expectedTitles: ['数据库连接超时故障'],
        expectedKeywords: ['数据库', '连接', '超时'],
        note: '默认问题 5；应能召回数据库连接池、连接等待和超时治理相关知识，对齐连接池耗尽证据链。',
      },
      {
        query: '凌晨偶尔会有订单失败',
        expectedTitles: ['上游依赖错误与波动'],
        expectedKeywords: ['订单', '偶发', '摇摆'],
        note: '默认问题 6；应能召回凌晨窗口偶发失败、跨服务摇摆和依赖波动相关知识。',
      },
    ],
  },
}

const DEFAULT_RAG_EVALUATION_INPUT = JSON.stringify(
  {
    label: '前端诊断全量基线 / Active Config',
    baselineSuiteId: FULL_SUITE_ID,
    presetId: 'active-config',
    cases: [
      ...FRONTEND_DIAGNOSIS_SAMPLES[SAMPLE_1_SUITE_ID].cases,
      ...FRONTEND_DIAGNOSIS_SAMPLES[SAMPLE_2_SUITE_ID].cases,
    ],
    configOverride: {
      finalTopK: 5,
      rerankTopN: 8,
      minVectorScore: 0.2,
    },
  },
  null,
  2,
)

type RagEvaluationInputCase = {
  query: string
  expectedTitles?: string[]
  expectedKeywords: string[]
  note?: string
}

function formatRatio(value: number) {
  return `${(value * 100).toFixed(1)}%`
}

function formatScore(value: number) {
  return Number.isFinite(value) ? value.toFixed(4) : '0.0000'
}

function formatDateTime(value: string) {
  if (!value) {
    return '未执行'
  }
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString()
}

function normalizeRagEvaluationPayload(rawText: string): {
  cases: RagEvaluationInputCase[]
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
} {
  const parsed = JSON.parse(rawText) as unknown
  const parsedObject =
    Array.isArray(parsed)
      ? { cases: parsed }
      : parsed && typeof parsed === 'object'
        ? (parsed as Record<string, unknown>)
        : null
  if (!parsedObject) {
    throw new Error('评测输入必须是 JSON 对象或 case 数组')
  }
  const rawCases = Array.isArray(parsedObject.cases) ? parsedObject.cases : []
  const cases = rawCases
    .map((item) => {
      if (!item || typeof item !== 'object') {
        return null
      }
      const record = item as Record<string, unknown>
      const query = typeof record.query === 'string' ? record.query.trim() : ''
      const expectedKeywords = Array.isArray(record.expectedKeywords)
        ? record.expectedKeywords
            .filter((keyword): keyword is string => typeof keyword === 'string')
            .map((keyword) => keyword.trim())
            .filter(Boolean)
        : []
      const expectedTitles = Array.isArray(record.expectedTitles)
        ? record.expectedTitles
            .filter((title): title is string => typeof title === 'string')
            .map((title) => title.trim())
            .filter(Boolean)
        : []
      const note = typeof record.note === 'string' ? record.note.trim() : undefined
      if (!query) {
        return null
      }
      const evaluationCase: RagEvaluationInputCase = { query, expectedKeywords }
      if (expectedTitles.length > 0) {
        evaluationCase.expectedTitles = expectedTitles
      }
      if (note) {
        evaluationCase.note = note
      }
      return evaluationCase
    })
    .filter((item): item is RagEvaluationInputCase => item !== null)
  if (cases.length === 0) {
    throw new Error('至少提供一条有效的评测 case')
  }
  const rawOverride =
    parsedObject.configOverride && typeof parsedObject.configOverride === 'object'
      ? (parsedObject.configOverride as Record<string, unknown>)
      : null
  const label = typeof parsedObject.label === 'string' ? parsedObject.label.trim() : undefined
  const baselineSuiteId = typeof parsedObject.baselineSuiteId === 'string' ? parsedObject.baselineSuiteId.trim() : undefined
  const presetId = typeof parsedObject.presetId === 'string' ? parsedObject.presetId.trim() : undefined
  const configOverride = rawOverride
    ? {
        vectorTopK: typeof rawOverride.vectorTopK === 'number' ? rawOverride.vectorTopK : undefined,
        keywordTopK: typeof rawOverride.keywordTopK === 'number' ? rawOverride.keywordTopK : undefined,
        finalTopK: typeof rawOverride.finalTopK === 'number' ? rawOverride.finalTopK : undefined,
        fusionTopK: typeof rawOverride.fusionTopK === 'number' ? rawOverride.fusionTopK : undefined,
        rerankTopN: typeof rawOverride.rerankTopN === 'number' ? rawOverride.rerankTopN : undefined,
        minVectorScore: typeof rawOverride.minVectorScore === 'number' ? rawOverride.minVectorScore : undefined,
      }
    : undefined
  return { cases, label, baselineSuiteId, presetId, configOverride }
}

function buildRagEvaluationInputTemplate(
  suite: RagBaselineSuiteData | undefined,
  preset: RagConfigPresetData | undefined,
  label: string,
) {
  return JSON.stringify(
    {
      label,
      baselineSuiteId: suite?.id,
      presetId: preset?.id,
      cases: suite?.cases ?? [],
      configOverride: preset?.configOverride ?? undefined,
    },
    null,
    2,
  )
}

function buildLocalSampleInput(sampleSuiteId: string, presetId: string, preset: RagConfigPresetData | undefined) {
  const sample = FRONTEND_DIAGNOSIS_SAMPLES[sampleSuiteId] ?? FRONTEND_DIAGNOSIS_SAMPLES[FULL_SUITE_ID]
  const label = `${sample.displayName} / ${preset?.displayName ?? 'Active Config'}`
  return JSON.stringify(
    {
      label,
      baselineSuiteId: sampleSuiteId,
      presetId,
      cases: sample.cases,
      configOverride: preset?.configOverride ?? undefined,
    },
    null,
    2,
  )
}

export function RagPage() {
  const [ragGovernance, setRagGovernance] = useState<RagGovernanceData | null>(null)
  const [ragEvaluationInput, setRagEvaluationInput] = useState(DEFAULT_RAG_EVALUATION_INPUT)
  const [ragBaselineSuiteId, setRagBaselineSuiteId] = useState(FULL_SUITE_ID)
  const [ragPresetId, setRagPresetId] = useState('active-config')
  const [ragEvaluationLabel, setRagEvaluationLabel] = useState('')
  const [ragEvaluationRunning, setRagEvaluationRunning] = useState(false)
  const [status, setStatus] = useState('')
  const [loading, setLoading] = useState(false)

  const historyEvaluations = useMemo(() => {
    if (!ragGovernance) {
      return []
    }
    const latestEvaluationId = ragGovernance.latestEvaluation.evaluationId
    return ragGovernance.recentEvaluations.filter((item) => item.evaluationId !== latestEvaluationId)
  }, [ragGovernance])

  async function loadPageData() {
    setLoading(true)
    try {
      const ragGovernanceData = await getRagGovernanceApi()
      setRagGovernance(ragGovernanceData)
      const baselineIds = ragGovernanceData.baselineSuites.map((suite) => suite.id)
      if (!baselineIds.includes(ragBaselineSuiteId)) {
        setRagBaselineSuiteId(baselineIds[0] ?? FULL_SUITE_ID)
      }
      if (!ragPresetId && ragGovernanceData.configPresets.length > 0) {
        setRagPresetId(ragGovernanceData.configPresets[0].id)
      }
      setStatus('')
    } catch (error) {
      setStatus(error instanceof Error ? `加载 RAG 页面失败：${error.message}` : '加载 RAG 页面失败')
    } finally {
      setLoading(false)
    }
  }

  async function handleRunRagEvaluation() {
    try {
      setRagEvaluationRunning(true)
      const payload = normalizeRagEvaluationPayload(ragEvaluationInput)
      const evaluationPayload = {
        ...payload,
        label: ragEvaluationLabel.trim() || payload.label,
        baselineSuiteId: ragBaselineSuiteId || payload.baselineSuiteId,
        presetId: ragPresetId || payload.presetId,
      }
      await evaluateRagApi(evaluationPayload)
      const refreshedGovernance = await getRagGovernanceApi()
      setRagGovernance(refreshedGovernance)
      setStatus(`RAG 评测已完成，共执行 ${payload.cases.length} 条 case`)
    } catch (error) {
      setStatus(error instanceof Error ? `RAG 评测失败：${error.message}` : 'RAG 评测失败')
    } finally {
      setRagEvaluationRunning(false)
    }
  }

  async function handleRebuildKnowledge() {
    try {
      setLoading(true)
      const message = await rebuildKnowledgeApi()
      await loadPageData()
      setStatus(message || '知识库全量重建成功')
    } catch (error) {
      setStatus(error instanceof Error ? `知识库全量重建失败：${error.message}` : '知识库全量重建失败')
    } finally {
      setLoading(false)
    }
  }

  function handleLoadRagSample(sampleSuiteId: string) {
    if (!ragGovernance) {
      return
    }
    const suite = ragGovernance.baselineSuites.find((item) => item.id === sampleSuiteId)
    const preset = ragGovernance.configPresets.find((item) => item.id === ragPresetId)
    const nextLabel = `${suite?.displayName ?? FRONTEND_DIAGNOSIS_SAMPLES[sampleSuiteId]?.displayName ?? 'Custom'} / ${preset?.displayName ?? 'Active Config'}`
    setRagBaselineSuiteId(sampleSuiteId)
    setRagEvaluationLabel(nextLabel)
    setRagEvaluationInput(
      suite
        ? buildRagEvaluationInputTemplate(suite, preset, nextLabel)
        : buildLocalSampleInput(sampleSuiteId, ragPresetId || 'active-config', preset),
    )
    setStatus(`已载入${FRONTEND_DIAGNOSIS_SAMPLES[sampleSuiteId]?.displayName ?? '评测样例'}与参数 preset ${preset?.displayName ?? 'Active Config'}`)
  }

  useEffect(() => {
    void loadPageData()
  }, [])

  return (
    <div className="page-stack">
      <section className="panel">
        <div className="panel-header split">
          <div>
            <h2>RAG 评测与治理</h2>
            <p>这里单独看检索参数、评测基线和最近结果，避免和系统页混在一起。</p>
          </div>
          <div className="toolbar-row">
            <button className="secondary-button" onClick={() => void loadPageData()} disabled={loading}>
              {loading ? '刷新中...' : '刷新'}
            </button>
            <button className="secondary-button" onClick={() => void handleRebuildKnowledge()} disabled={loading}>
              全量重建索引
            </button>
            <button className="secondary-button" onClick={() => handleLoadRagSample(FULL_SUITE_ID)}>
              载入样例
            </button>
            <button
              className="primary-button"
              onClick={() => void handleRunRagEvaluation()}
              disabled={ragEvaluationRunning}
            >
              {ragEvaluationRunning ? '评测中...' : '运行评测'}
            </button>
          </div>
        </div>
        {status ? <div className="page-banner info">{status}</div> : null}
      </section>

      {ragGovernance ? (
        <>
          <section className="panel">
            <div className="panel-header">
              <div>
                <h2>当前检索参数</h2>
                <p>这里展示当前生效的召回、融合和重排参数。修改知识标题提取或排序策略后，建议先点一次 `全量重建索引` 再重跑评测。</p>
              </div>
            </div>
            <div className="health-grid">
              <article className="health-card">
                <div className="health-title">Vector Top K</div>
                <strong>{ragGovernance.activeConfig.vectorTopK}</strong>
              </article>
              <article className="health-card">
                <div className="health-title">Keyword Top K</div>
                <strong>{ragGovernance.activeConfig.keywordTopK}</strong>
              </article>
              <article className="health-card">
                <div className="health-title">Final Top K</div>
                <strong>{ragGovernance.activeConfig.finalTopK}</strong>
              </article>
              <article className="health-card">
                <div className="health-title">Rerank Top N</div>
                <strong>{ragGovernance.activeConfig.rerankTopN}</strong>
              </article>
              <article className="health-card">
                <div className="health-title">Min Vector Score</div>
                <strong>{ragGovernance.activeConfig.minVectorScore}</strong>
              </article>
              <article className="health-card">
                <div className="health-title">Reranker</div>
                <strong>{ragGovernance.activeConfig.rerankerConfigured ? '已启用' : '未启用'}</strong>
              </article>
            </div>
          </section>

          <section className="panel">
            <div className="panel-header">
              <div>
                <h2>评测输入</h2>
                <p>先选基线和参数预设，再调整 JSON 输入。</p>
              </div>
            </div>
            <div className="detail-grid">
              <div>
                <strong>基线套件</strong>
                <select
                  value={ragBaselineSuiteId}
                  onChange={(event) => setRagBaselineSuiteId(event.target.value)}
                  style={{ width: '100%', marginTop: 12, border: '1px solid #cbd5e1', borderRadius: 12, padding: 12, background: '#fff' }}
                >
                  {(ragGovernance.baselineSuites || []).map((suite) => (
                    <option key={suite.id} value={suite.id}>
                      {suite.displayName}
                    </option>
                  ))}
                </select>
                <div className="empty-mini" style={{ marginTop: 8 }}>
                  {ragGovernance.baselineSuites.find((suite) => suite.id === ragBaselineSuiteId)?.description || '选择内置评测集'}
                </div>
              </div>
              <div>
                <strong>参数预设</strong>
                <select
                  value={ragPresetId}
                  onChange={(event) => setRagPresetId(event.target.value)}
                  style={{ width: '100%', marginTop: 12, border: '1px solid #cbd5e1', borderRadius: 12, padding: 12, background: '#fff' }}
                >
                  {(ragGovernance.configPresets || []).map((preset) => (
                    <option key={preset.id} value={preset.id}>
                      {preset.displayName}
                    </option>
                  ))}
                </select>
                <div className="empty-mini" style={{ marginTop: 8 }}>
                  {ragGovernance.configPresets.find((preset) => preset.id === ragPresetId)?.description || '选择常用参数组合'}
                </div>
              </div>
              <div>
                <strong>评测标签</strong>
                <input
                  value={ragEvaluationLabel}
                  onChange={(event) => setRagEvaluationLabel(event.target.value)}
                  placeholder="例如：核心运维 / Balanced Rerank"
                  style={{ width: '100%', marginTop: 12, border: '1px solid #cbd5e1', borderRadius: 12, padding: 12, background: '#fff' }}
                />
              </div>
              <div className="detail-full">
                <strong>评测输入 JSON</strong>
                <textarea
                  value={ragEvaluationInput}
                  onChange={(event) => setRagEvaluationInput(event.target.value)}
                  rows={14}
                  placeholder="输入 RAG 评测请求 JSON"
                  style={{ width: '100%', marginTop: 12, border: '1px solid #cbd5e1', borderRadius: 12, padding: 12, background: '#fff' }}
                />
              </div>
            </div>
          </section>

          <section className="panel">
            <div className="panel-header">
              <div>
                <h2>最近结果</h2>
                <p>这里看命中率、MRR 和最近各条 case 的检索结果。命中口径优先按期望文档标题，其次按关键词兜底；融合排序分值是排序分，不是概率。</p>
              </div>
            </div>
            <div className="health-grid">
              <article className="health-card">
                <div className="health-title">Hit@1</div>
                <strong>{formatRatio(ragGovernance.latestEvaluation.summary.hitAt1Ratio)}</strong>
              </article>
              <article className="health-card">
                <div className="health-title">Hit@3</div>
                <strong>{formatRatio(ragGovernance.latestEvaluation.summary.hitAt3Ratio)}</strong>
              </article>
              <article className="health-card">
                <div className="health-title">MRR</div>
                <strong>{formatScore(ragGovernance.latestEvaluation.summary.meanReciprocalRank)}</strong>
              </article>
              <article className="health-card">
                <div className="health-title">评测样例数</div>
                <strong>{ragGovernance.latestEvaluation.summary.totalCases}</strong>
              </article>
              <article className="health-card">
                <div className="health-title">Final Top K</div>
                <strong>{ragGovernance.latestEvaluation.effectiveConfig.finalTopK}</strong>
              </article>
            </div>
            <div className="detail-grid" style={{ marginTop: 16 }}>
              <div className="detail-full">
                <strong>最近 Case 结果</strong>
                <div className="selector-list" style={{ marginTop: 12 }}>
                  {ragGovernance.latestEvaluation.cases.length > 0 ? (
                    ragGovernance.latestEvaluation.cases.map((item, index) => (
                      <div key={`${item.query}-${index}`} className="status-item">
                        <strong>{item.query}</strong>
                        <span>命中名次：{item.matchedRank || '未命中'}</span>
                        <span>Hit@1：{item.hitAt1 ? '是' : '否'}</span>
                        <span>Hit@3：{item.hitAt3 ? '是' : '否'}</span>
                        <span>融合排序分值：{formatScore(item.topScore)}</span>
                        <span>
                          候选数：向量 {item.vectorCandidateCount} / 关键词 {item.keywordCandidateCount}
                        </span>
                        <span>期望文档：{(item.expectedTitles || []).join(', ') || '无'}</span>
                        <span>期望关键词：{(item.expectedKeywords || []).join(', ') || '无'}</span>
                        <span>备注：{item.note || '无'}</span>
                        <pre className="json-block" style={{ marginTop: 8 }}>
                          {JSON.stringify(item.topResults, null, 2)}
                        </pre>
                      </div>
                    ))
                  ) : (
                    <div className="empty-state compact">运行评测后，这里会展示每条 case 的命中名次和结果。</div>
                  )}
                </div>
              </div>
              <div className="detail-full">
                <strong>最近评测历史</strong>
                <div className="selector-list" style={{ marginTop: 12 }}>
                  {historyEvaluations.length > 0 ? (
                    historyEvaluations.map((item) => (
                      <div key={item.evaluationId} className="status-item">
                        <strong>{item.evaluationLabel || '未命名评测'}</strong>
                        <span>执行时间：{formatDateTime(item.evaluatedAt)}</span>
                        <span>基线套件：{item.baselineSuiteId || 'custom'}</span>
                        <span>参数预设：{item.presetId || 'active-config'}</span>
                        <span>Hit@1：{formatRatio(item.summary.hitAt1Ratio)}</span>
                        <span>Hit@3：{formatRatio(item.summary.hitAt3Ratio)}</span>
                        <span>MRR：{formatScore(item.summary.meanReciprocalRank)}</span>
                        <pre className="json-block" style={{ marginTop: 8 }}>
                          {JSON.stringify(item.effectiveConfig, null, 2)}
                        </pre>
                      </div>
                    ))
                  ) : (
                    <div className="empty-state compact">多跑几次评测后，这里会保留最近结果方便横向比较。</div>
                  )}
                </div>
              </div>
            </div>
          </section>
        </>
      ) : (
        <section className="panel">
          <div className="empty-state">RAG 数据加载中</div>
        </section>
      )}
    </div>
  )
}
