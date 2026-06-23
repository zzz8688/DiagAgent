import { useEffect, useMemo, useState } from 'react'
import { getExportableMcpToolsApi, getMcpRegistryApi } from '../api/index.ts'
import type { GenericMap, McpRegistryData } from '../types'

const SELF_MCP_SERVER_ID = 'diagagent-mcp-server'

function readStringValue(record: GenericMap, keys: string[]) {
  for (const key of keys) {
    const value = record[key]
    if (typeof value === 'string' && value.trim()) {
      return value
    }
  }
  return '未命名'
}

function readToolDisplayName(record: GenericMap) {
  return readStringValue(record, ['displayName', 'toolName', 'name', 'id'])
}

function readResourceDisplayName(record: GenericMap) {
  return readStringValue(record, ['title', 'name', 'uri', 'id'])
}

function readPromptDisplayName(record: GenericMap) {
  return readStringValue(record, ['title', 'promptName', 'name', 'id'])
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

export function McpPage() {
  const [mcpRegistry, setMcpRegistry] = useState<McpRegistryData>({
    servers: [],
    tools: [],
    resources: [],
    prompts: [],
    capabilitySnapshots: [],
  })
  const [exportableTools, setExportableTools] = useState<GenericMap[]>([])
  const [selectedMcpServerId, setSelectedMcpServerId] = useState('')
  const [status, setStatus] = useState('')
  const [loading, setLoading] = useState(false)

  const externalServers = useMemo(
    () => mcpRegistry.servers.filter((server) => readRecordId(server, ['serverId', 'id', 'name']) !== SELF_MCP_SERVER_ID),
    [mcpRegistry.servers],
  )

  const externalToolCount = useMemo(
    () =>
      mcpRegistry.tools.filter((tool) => readRecordId(tool, ['serverId', 'providerId', 'id', 'name']) !== SELF_MCP_SERVER_ID)
        .length,
    [mcpRegistry.tools],
  )

  const summaryItems = useMemo(
    () => [
      { label: '外部 MCP 服务', value: externalServers.length },
      { label: '已注册外部工具', value: externalToolCount },
      { label: '自导出工具', value: exportableTools.length },
    ],
    [externalServers.length, externalToolCount, exportableTools.length],
  )

  const selectedServer = useMemo(
    () => externalServers.find((server) => readRecordId(server, ['serverId', 'id', 'name']) === selectedMcpServerId) ?? null,
    [externalServers, selectedMcpServerId],
  )

  const selectedServerTools = useMemo(
    () =>
      mcpRegistry.tools.filter(
        (tool) => readRecordId(tool, ['serverId', 'providerId', 'id', 'name']) === selectedMcpServerId,
      ),
    [mcpRegistry.tools, selectedMcpServerId],
  )

  const selectedServerResources = useMemo(
    () =>
      mcpRegistry.resources.filter(
        (resource) => readRecordId(resource, ['serverId', 'providerId', 'id', 'name']) === selectedMcpServerId,
      ),
    [mcpRegistry.resources, selectedMcpServerId],
  )

  const selectedServerPrompts = useMemo(
    () =>
      mcpRegistry.prompts.filter(
        (prompt) => readRecordId(prompt, ['serverId', 'providerId', 'id', 'name']) === selectedMcpServerId,
      ),
    [mcpRegistry.prompts, selectedMcpServerId],
  )

  const selectedCapabilitySnapshot = useMemo(
    () =>
      mcpRegistry.capabilitySnapshots.find(
        (snapshot) => readRecordId(snapshot, ['serverId', 'id', 'name']) === selectedMcpServerId,
      ) ?? null,
    [mcpRegistry.capabilitySnapshots, selectedMcpServerId],
  )

  const selectedServerCounts = useMemo(
    () => ({
      tools: selectedServerTools.length || readNumberValue(selectedCapabilitySnapshot ?? {}, ['toolCount', 'userVisibleToolCount']),
      resources:
        selectedServerResources.length || readNumberValue(selectedCapabilitySnapshot ?? {}, ['resourceCount', 'userVisibleResourceCount']),
      prompts:
        selectedServerPrompts.length || readNumberValue(selectedCapabilitySnapshot ?? {}, ['promptCount', 'userVisiblePromptCount']),
    }),
    [selectedCapabilitySnapshot, selectedServerPrompts.length, selectedServerResources.length, selectedServerTools.length],
  )

  async function loadPageData() {
    setLoading(true)
    try {
      const [mcpRegistryData, exportableData] = await Promise.all([
        getMcpRegistryApi(),
        getExportableMcpToolsApi(),
      ])
      setMcpRegistry(mcpRegistryData)
      setExportableTools(exportableData)
      const externalServerIds = mcpRegistryData.servers
        .map((server) => readRecordId(server, ['serverId', 'id', 'name']))
        .filter((serverId) => serverId && serverId !== SELF_MCP_SERVER_ID)
      const firstServerId = externalServerIds[0] ?? ''
      setSelectedMcpServerId((current) => (current && externalServerIds.includes(current) ? current : firstServerId))
      setStatus('')
    } catch (error) {
      setStatus(error instanceof Error ? `加载 MCP 协议页失败：${error.message}` : '加载 MCP 协议页失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadPageData()
  }, [])

  return (
    <div className="page-stack">
      <section className="panel">
        <div className="panel-header split">
          <div>
            <h2>MCP 协议总览</h2>
            <p>这里只看协议注册侧：外部 MCP 服务接入情况，以及 `DiagAgent` 自己对外导出的 MCP 能力；不展示主智能体实际使用的偏好工具。</p>
          </div>
          <button className="secondary-button" onClick={() => void loadPageData()} disabled={loading}>
            {loading ? '刷新中...' : '刷新'}
          </button>
        </div>
        {status ? <div className="page-banner info">{status}</div> : null}
        <div className="summary-grid">
          {summaryItems.map((item) => (
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
            <h2>MCP 服务</h2>
            <p>这里只看外部 MCP 服务注册到的协议信息，不把 `DiagAgent` 自导出的内容混在这里，也不代表主智能体最终实际使用结果。</p>
          </div>
        </div>
        <div className="stack-grid">
          <article className="status-card">
            <h3>MCP 服务</h3>
            <div className="selector-list">
              {externalServers.map((server, index) => {
                const serverId = readRecordId(server, ['serverId', 'id', 'name'])
                return (
                  <button
                    key={`${serverId}-${index}`}
                    className={`selector-button ${selectedMcpServerId === serverId ? 'active' : ''}`}
                    onClick={() => setSelectedMcpServerId(serverId)}
                    type="button"
                  >
                    <strong>{readStringValue(server, ['displayName', 'serverId', 'name', 'id'])}</strong>
                    <span>{readStringValue(server, ['transport', 'status', 'state'])}</span>
                  </button>
                )
              })}
              {externalServers.length === 0 ? <div className="empty-state compact">暂无外部 MCP 服务。</div> : null}
            </div>
          </article>
          <article className="status-card">
            <h3>服务详情</h3>
            {!selectedMcpServerId ? <div className="empty-state compact">请选择 MCP 服务。</div> : null}
            {selectedMcpServerId && selectedServer ? (
              <div className="summary-grid">
                <article className="summary-card">
                  <span>服务名</span>
                  <strong>{readStringValue(selectedServer, ['displayName', 'serverId', 'name', 'id'])}</strong>
                </article>
                <article className="summary-card">
                  <span>传输方式</span>
                  <strong>{readStringValue(selectedServer, ['transport', 'transportType', 'state'])}</strong>
                </article>
                <article className="summary-card">
                  <span>状态</span>
                  <strong>{readStringValue(selectedServer, ['status', 'state'])}</strong>
                </article>
                <article className="summary-card">
                  <span>工具数</span>
                  <strong>{selectedServerCounts.tools}</strong>
                </article>
                <article className="summary-card">
                  <span>资源数</span>
                  <strong>{selectedServerCounts.resources}</strong>
                </article>
                <article className="summary-card">
                  <span>Prompt 数</span>
                  <strong>{selectedServerCounts.prompts}</strong>
                </article>
              </div>
            ) : null}
            {selectedMcpServerId && selectedServer ? (
              <div className="stack-grid">
                <article className="status-card">
                  <h3>已注册工具</h3>
                  <div className="selector-list">
                    {selectedServerTools.map((tool, index) => (
                      <button
                        key={`${readRecordId(tool, ['toolName', 'name', 'id'])}-${index}`}
                        className="selector-button"
                        type="button"
                      >
                        <strong>{readToolDisplayName(tool)}</strong>
                        <span>{readStringValue(tool, ['description'])}</span>
                      </button>
                    ))}
                    {selectedServerTools.length === 0 ? <div className="empty-state compact">暂无已注册工具。</div> : null}
                  </div>
                </article>
                <article className="status-card">
                  <h3>已注册资源</h3>
                  <div className="selector-list">
                    {selectedServerResources.map((resource, index) => (
                      <button
                        key={`${readRecordId(resource, ['title', 'name', 'uri', 'id'])}-${index}`}
                        className="selector-button"
                        type="button"
                      >
                        <strong>{readResourceDisplayName(resource)}</strong>
                        <span>{readStringValue(resource, ['mimeType', 'type'])}</span>
                      </button>
                    ))}
                    {selectedServerResources.length === 0 ? <div className="empty-state compact">暂无已注册资源。</div> : null}
                  </div>
                </article>
                <article className="status-card">
                  <h3>已注册 Prompt</h3>
                  <div className="selector-list">
                    {selectedServerPrompts.map((prompt, index) => (
                      <button
                        key={`${readRecordId(prompt, ['title', 'promptName', 'name', 'id'])}-${index}`}
                        className="selector-button"
                        type="button"
                      >
                        <strong>{readPromptDisplayName(prompt)}</strong>
                        <span>{readStringValue(prompt, ['description', 'type'])}</span>
                      </button>
                    ))}
                    {selectedServerPrompts.length === 0 ? <div className="empty-state compact">暂无已注册 Prompt。</div> : null}
                  </div>
                </article>
              </div>
            ) : null}
          </article>
        </div>
      </section>

      <section className="panel">
        <div className="panel-header">
          <div>
            <h2>自导出 MCP</h2>
            <p>这里展示 `DiagAgent` 自己对外提供的 MCP 工具，给其他 MCP client 接入使用。</p>
          </div>
        </div>
        <div className="table-wrapper">
          <table className="data-table">
            <thead>
              <tr>
                <th>名称</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              {exportableTools.map((tool, index) => (
                <tr key={`${readStringValue(tool, ['name', 'toolName', 'id'])}-${index}`}>
                  <td>{readStringValue(tool, ['name', 'toolName', 'id'])}</td>
                  <td>{readStringValue(tool, ['description', 'summary'])}</td>
                </tr>
              ))}
              {!loading && exportableTools.length === 0 ? (
                <tr>
                  <td colSpan={2}>
                    <div className="empty-state">暂无自导出 MCP 工具。</div>
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  )
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
