import { useEffect, useMemo, useState } from 'react'
import { getA2aAgentDetailApi, getA2aRecentEventsApi, getA2aRegistryApi, getA2aStateApi } from '../api/index.ts'
import type { A2aStateData, GenericMap } from '../types'

function readStringValue(record: GenericMap, keys: string[]) {
  for (const key of keys) {
    const value = record[key]
    if (typeof value === 'string' && value.trim()) {
      return value
    }
  }
  return '-'
}

function readRecordId(record: GenericMap, keys: string[]) {
  for (const key of keys) {
    const value = record[key]
    if (typeof value === 'string' && value.trim()) {
      return value
    }
  }
  return ''
}

function readBooleanValue(record: GenericMap, keys: string[]) {
  for (const key of keys) {
    const value = record[key]
    if (typeof value === 'boolean') {
      return value
    }
  }
  return false
}

function readNumberValue(record: GenericMap, keys: string[]) {
  for (const key of keys) {
    const value = record[key]
    if (typeof value === 'number' && Number.isFinite(value)) {
      return value
    }
  }
  return 0
}

function formatJson(value: unknown) {
  return JSON.stringify(value, null, 2)
}

function inferAgentRole(record: GenericMap) {
  const fingerprint = [
    readStringValue(record, ['agentId', 'id', 'name']),
    readStringValue(record, ['agentName', 'description']),
    formatJson(record['agentCard'] ?? record['metadata'] ?? {}),
  ]
    .join(' ')
    .toLowerCase()
  return fingerprint.includes('critic') || fingerprint.includes('批评')
    ? '批评子智能体'
    : '子智能体'
}

type A2aPlatformAgent = {
  agentId: string
  name: string
  endpoint: string
  transport: string
  status: string
  connectionState: string
  skillCount: number
  roleLabel: string
  localAgent: boolean
  detailSource: GenericMap
}

export function A2aPage() {
  const [registryAgents, setRegistryAgents] = useState<GenericMap[]>([])
  const [stateData, setStateData] = useState<A2aStateData>({ agents: [] })
  const [recentEvents, setRecentEvents] = useState<GenericMap[]>([])
  const [selectedAgentId, setSelectedAgentId] = useState('')
  const [agentDetail, setAgentDetail] = useState<GenericMap | null>(null)
  const [status, setStatus] = useState('')
  const [loading, setLoading] = useState(false)
  const [detailLoading, setDetailLoading] = useState(false)

  const platformAgents = useMemo<A2aPlatformAgent[]>(() => {
    const runtimeStateById = new Map<string, GenericMap>()
    for (const agent of stateData.agents) {
      runtimeStateById.set(agent.agentId, agent as unknown as GenericMap)
    }
    return registryAgents
      .map((registryAgent) => {
        const agentId = readRecordId(registryAgent, ['agentId', 'id', 'name'])
        const runtimeState = runtimeStateById.get(agentId) ?? {}
        const mergedAgent = { ...registryAgent, ...runtimeState }
        return {
          agentId,
          name: readStringValue(mergedAgent, ['agentName', 'name', 'agentId', 'id']),
          endpoint: readStringValue(mergedAgent, ['endpoint']),
          transport: readStringValue(mergedAgent, ['transport']),
          status: readStringValue(mergedAgent, ['status']),
          connectionState: readStringValue(mergedAgent, ['connectionState']),
          skillCount: readNumberValue(mergedAgent, ['skillCount']),
          roleLabel: inferAgentRole(mergedAgent),
          localAgent: readBooleanValue(mergedAgent, ['localAgent', 'local']),
          detailSource: mergedAgent,
        }
      })
      .filter((agent) => agent.agentId)
      .filter((agent) => !agent.localAgent)
  }, [registryAgents, stateData.agents])

  const visibleAgentIds = useMemo(() => new Set(platformAgents.map((agent) => agent.agentId)), [platformAgents])

  const connectedCount = useMemo(
    () => platformAgents.filter((agent) => (agent.connectionState || '').toUpperCase() === 'CONNECTED').length,
    [platformAgents],
  )

  const enabledCount = useMemo(
    () => platformAgents.filter((agent) => (agent.status || '').toUpperCase() !== 'DISABLED').length,
    [platformAgents],
  )

  const visibleRecentEvents = useMemo(
    () =>
      recentEvents.filter((event) => {
        const agentId = readRecordId(event, ['agentId'])
        if (!agentId) {
          return true
        }
        return visibleAgentIds.has(agentId)
      }),
    [recentEvents, visibleAgentIds],
  )

  async function loadPageData() {
    setLoading(true)
    try {
      const [registryData, runtimeState, events] = await Promise.all([
        getA2aRegistryApi(),
        getA2aStateApi(),
        getA2aRecentEventsApi(20),
      ])
      setRegistryAgents(registryData.agents)
      setStateData(runtimeState)
      setRecentEvents(events)
      const firstAgentId = registryData.agents
        .map((agent) => {
          const merged = {
            ...agent,
            ...(runtimeState.agents.find((stateAgent) => stateAgent.agentId === readRecordId(agent, ['agentId', 'id', 'name'])) ?? {}),
          }
          return {
            agentId: readRecordId(merged, ['agentId', 'id', 'name']),
            localAgent: readBooleanValue(merged, ['localAgent', 'local']),
          }
        })
        .find((agent) => agent.agentId && !agent.localAgent)?.agentId ?? ''
      setSelectedAgentId((current) => current || firstAgentId)
      setStatus('')
    } catch (error) {
      setStatus(error instanceof Error ? `加载 A2A 页面失败：${error.message}` : '加载 A2A 页面失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadPageData()
  }, [])

  useEffect(() => {
    const firstVisibleAgentId = platformAgents[0]?.agentId ?? ''
    setSelectedAgentId((current) => {
      if (current && platformAgents.some((agent) => agent.agentId === current)) {
        return current
      }
      return firstVisibleAgentId
    })
  }, [platformAgents])

  useEffect(() => {
    if (!selectedAgentId) {
      setAgentDetail(null)
      return
    }
    async function loadAgentDetail() {
      setDetailLoading(true)
      try {
        setAgentDetail(await getA2aAgentDetailApi(selectedAgentId))
      } catch (error) {
        setStatus(error instanceof Error ? `加载 A2A Agent 详情失败：${error.message}` : '加载 A2A Agent 详情失败')
        setAgentDetail(null)
      } finally {
        setDetailLoading(false)
      }
    }
    void loadAgentDetail()
  }, [selectedAgentId])

  return (
    <div className="page-stack">
      <section className="panel">
        <div className="panel-header split">
          <div>
            <h2>A2A 平台总览</h2>
            <p>主智能体作为 A2A Client / 平台编排者存在，这里只展示通过 Agent Card 注册的远端子智能体与批评子智能体。</p>
          </div>
          <button className="secondary-button" onClick={() => void loadPageData()} disabled={loading}>
            {loading ? '刷新中...' : '刷新'}
          </button>
        </div>
        {status ? <div className="page-banner info">{status}</div> : null}
        <div className="summary-grid">
          <article className="summary-card">
            <span>已注册子智能体</span>
            <strong>{platformAgents.length}</strong>
          </article>
          <article className="summary-card">
            <span>已启用子智能体</span>
            <strong>{enabledCount}</strong>
          </article>
          <article className="summary-card">
            <span>已连接远端</span>
            <strong>{connectedCount}</strong>
          </article>
          <article className="summary-card">
            <span>最近事件</span>
            <strong>{visibleRecentEvents.length}</strong>
          </article>
        </div>
      </section>

      <section className="panel">
        <div className="panel-header">
          <div>
            <h2>远端 Agent 详情</h2>
            <p>平台侧只保留远端 A2A Server 的注册与运行信息；主智能体和 critic 的协作应通过 A2A 协议完成，而不是把主智能体自身渲染进列表。</p>
          </div>
        </div>
        <div className="stack-grid">
          <article className="status-card">
            <h3>已注册远端 Agent</h3>
            <div className="selector-list">
              {platformAgents.map((agent) => (
                <button
                  key={agent.agentId}
                  className={`selector-button ${selectedAgentId === agent.agentId ? 'active' : ''}`}
                  onClick={() => setSelectedAgentId(agent.agentId)}
                  type="button"
                >
                  <strong>{agent.name || agent.agentId}</strong>
                  <span>{agent.roleLabel}</span>
                  <span>{agent.connectionState !== '-' ? agent.connectionState : agent.status}</span>
                </button>
              ))}
              {platformAgents.length === 0 ? <div className="empty-state compact">暂无已注册的远端 A2A 子智能体。</div> : null}
            </div>
          </article>
          <article className="status-card">
            <h3>注册与运行状态</h3>
            <div className="status-list">
              {platformAgents.map((agent) => (
                <div key={`${agent.agentId}-state`} className="status-item">
                  <strong>{agent.name || agent.agentId}</strong>
                  <span>角色：{agent.roleLabel}</span>
                  <span>注册状态：{agent.status || '-'}</span>
                  <span>连接状态：{agent.connectionState || '-'}</span>
                  <span>传输方式：{agent.transport || '-'}</span>
                  <span>接入地址：{agent.endpoint || '-'}</span>
                  <span>技能数：{agent.skillCount}</span>
                </div>
              ))}
              {platformAgents.length === 0 ? <div className="empty-state compact">暂无远端 Agent 运行状态数据。</div> : null}
            </div>
          </article>
          <article className="status-card">
            <h3>Agent 详情 JSON</h3>
            <pre className="json-block">
              {selectedAgentId ? formatJson(agentDetail ?? { loading: detailLoading }) : '请选择远端 A2A Agent'}
            </pre>
          </article>
        </div>
      </section>

      <section className="panel">
        <div className="panel-header">
          <div>
            <h2>最近事件</h2>
            <p>这里保留远端子智能体相关的最近 A2A 事件，方便排查 agent card 发现、任务发出、回收和结束。</p>
          </div>
        </div>
        <div className="selector-list">
          {visibleRecentEvents.map((event, index) => (
            <div key={`${readRecordId(event, ['taskId', 'id'])}-${index}`} className="status-item">
              <strong>{readStringValue(event, ['type', 'eventType', 'kind'])}</strong>
              <span>任务：{readStringValue(event, ['taskId', 'id'])}</span>
              <span>Agent：{readStringValue(event, ['agentId', 'role', 'source'])}</span>
              <span>时间：{readStringValue(event, ['timestamp', 'createdAt', 'time'])}</span>
            </div>
          ))}
          {visibleRecentEvents.length === 0 ? <div className="empty-state compact">暂无远端子智能体相关事件。</div> : null}
        </div>
      </section>
    </div>
  )
}
