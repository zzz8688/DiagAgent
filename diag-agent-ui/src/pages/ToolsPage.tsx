import { useEffect, useMemo, useState } from 'react'
import {
  getCapabilityManifestApi,
  getMcpRegistryApi,
} from '../api/index.ts'
import type { CapabilityManifestData, CapabilityManifestEntryData, GenericMap } from '../types'

const emptyManifest: CapabilityManifestData = {
  viewVersion: '',
  readOnly: false,
  totalEntryCount: 0,
  countsByKind: {},
  entries: [],
}

export function ToolsPage() {
  const [capabilityManifest, setCapabilityManifest] = useState<CapabilityManifestData>(emptyManifest)
  const [mcpRegistry, setMcpRegistry] = useState({
    servers: [] as GenericMap[],
    tools: [] as GenericMap[],
    resources: [] as GenericMap[],
    prompts: [] as GenericMap[],
    capabilitySnapshots: [] as GenericMap[],
  })
  const [status, setStatus] = useState('')
  const [loading, setLoading] = useState(false)

  const localTools = useMemo(
    () => capabilityManifest.entries.filter((entry) => entry.kind === 'tool' && entry.source === 'local'),
    [capabilityManifest.entries],
  )

  const mcpTools = useMemo(
    () => capabilityManifest.entries.filter((entry) => entry.kind === 'tool' && entry.source === 'mcp'),
    [capabilityManifest.entries],
  )

  const totalTools = useMemo(() => localTools.length + mcpTools.length, [localTools.length, mcpTools.length])

  function readMetadataValue(entry: CapabilityManifestEntryData, keys: string[]) {
    for (const key of keys) {
      const value = entry.metadata?.[key]
      if (typeof value === 'string' && value.trim()) {
        return value
      }
    }
    return '-'
  }

  async function loadPageData() {
    setLoading(true)
    try {
      const [manifestData, registryData] = await Promise.all([
        getCapabilityManifestApi(),
        getMcpRegistryApi(),
      ])
      setCapabilityManifest(manifestData)
      setMcpRegistry(registryData)
      setStatus('')
    } catch (error) {
      setStatus(error instanceof Error ? `加载工具页失败：${error.message}` : '加载工具页失败')
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
            <h2>工具总览</h2>
            <p>这里就是主智能体当前实际使用的统一工具池，和 runtime capability manifest 保持一致。</p>
          </div>
          <button className="secondary-button" onClick={() => void loadPageData()} disabled={loading}>
            {loading ? '刷新中...' : '刷新'}
          </button>
        </div>
        {status ? <div className="page-banner info">{status}</div> : null}
        <div className="summary-grid">
          <article className="summary-card">
            <span>统一工具池</span>
            <strong>{totalTools}</strong>
          </article>
          <article className="summary-card">
            <span>本地工具</span>
            <strong>{localTools.length}</strong>
          </article>
          <article className="summary-card">
            <span>MCP 工具</span>
            <strong>{mcpTools.length}</strong>
          </article>
          <article className="summary-card">
            <span>MCP 服务</span>
            <strong>{mcpRegistry.servers.length}</strong>
          </article>
        </div>
      </section>

      <section className="panel">
        <div className="panel-header">
          <div>
            <h2>本地工具</h2>
            <p>这里显示 capability manifest 中 `source=local` 的工具，也就是运行时实际按本地后端执行的工具。</p>
          </div>
        </div>
        <div className="table-wrapper">
          <table className="data-table">
            <thead>
              <tr>
                <th>工具池标识</th>
                <th>名称</th>
                <th>绑定方式</th>
                <th>执行后端</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              {localTools.map((tool) => (
                <tr key={tool.id}>
                  <td>{tool.id}</td>
                  <td>{tool.displayName || tool.id}</td>
                  <td>{readMetadataValue(tool, ['frameworkBindingMode'])}</td>
                  <td>{readMetadataValue(tool, ['preferredBackend', 'providerId', 'transport'])}</td>
                  <td>{tool.description || '-'}</td>
                </tr>
              ))}
              {!loading && localTools.length === 0 ? (
                <tr>
                  <td colSpan={5}>
                    <div className="empty-state">暂无本地工具。</div>
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel">
        <div className="panel-header">
          <div>
            <h2>MCP 工具</h2>
            <p>这里展示 capability manifest 中 `source=mcp` 的工具；工具页口径就是主智能体运行时实际可见口径。</p>
          </div>
        </div>
        <div className="table-wrapper">
          <table className="data-table">
            <thead>
              <tr>
                <th>工具池标识</th>
                <th>名称</th>
                <th>MCP 服务</th>
                <th>传输方式</th>
                <th>需要审批</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              {mcpTools.map((tool) => (
                <tr key={tool.id}>
                  <td>{tool.id}</td>
                  <td>{tool.displayName || tool.id}</td>
                  <td>{String(tool.metadata?.providerId ?? '-')}</td>
                  <td>{String(tool.metadata?.transport ?? '-')}</td>
                  <td>{tool.executionPolicy?.requiresApproval ? '是' : '否'}</td>
                  <td>{tool.description || '-'}</td>
                </tr>
              ))}
              {!loading && mcpTools.length === 0 ? (
                <tr>
                  <td colSpan={6}>
                    <div className="empty-state">暂无 MCP 工具。</div>
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
