import { useEffect, useMemo, useState } from 'react'
import { getSandboxAuditsApi, getSandboxConfigApi } from '../api/index.ts'
import type { SandboxAuditRecord, SandboxConfigData } from '../types'

function formatTime(value?: string) {
  if (!value) {
    return '未知'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString('zh-CN', { hour12: false })
}

function formatBoolean(value: boolean, trueLabel = '是', falseLabel = '否') {
  return value ? trueLabel : falseLabel
}

function formatListPreview(values: string[], emptyLabel = '无') {
  if (!values || values.length === 0) {
    return emptyLabel
  }
  if (values.length <= 3) {
    return values.join('、')
  }
  return `${values.slice(0, 3).join('、')} 等 ${values.length} 项`
}

function describeAuditResult(audit: SandboxAuditRecord) {
  if (audit.resultLabel) {
    return audit.resultLabel
  }
  if (audit.success) {
    return 'SUCCESS'
  }
  if (audit.cancelled) {
    return 'CANCELLED'
  }
  if (audit.timedOut) {
    return 'TIMEOUT'
  }
  return 'FAILED'
}

const emptyConfig: SandboxConfigData = {
  enabled: false,
  mode: 'UNKNOWN',
  approvalBackend: 'UNKNOWN',
  autoApproveAskRequests: false,
  maxAuditRecords: 0,
  defaultProfile: {
    profileName: 'default',
    readOnlyFilesystem: false,
    allowReadPaths: [],
    allowWritePaths: [],
    denyReadPaths: [],
    denyWritePaths: [],
    networkEnabled: false,
    allowedDomains: [],
    deniedDomains: [],
    allowUnsandboxedFallback: false,
    maxWallClockTime: 'PT30S',
    maxOutputBytes: 0,
    environmentAllowlist: {},
  },
  rules: [],
}

export function SandboxPage() {
  const [config, setConfig] = useState<SandboxConfigData>(emptyConfig)
  const [audits, setAudits] = useState<SandboxAuditRecord[]>([])
  const [status, setStatus] = useState('')
  const [loading, setLoading] = useState(false)
  const [loadFailed, setLoadFailed] = useState(false)

  const summaryItems = useMemo(
    () => [
      { label: '启用状态', value: config.enabled ? '已启用' : '已关闭' },
      { label: '主链关系', value: '非必经环节' },
      { label: '审计记录数', value: String(audits.length) },
      { label: '执行模式', value: config.mode },
    ],
    [audits.length, config.enabled, config.mode],
  )

  const latestAudits = useMemo(() => audits.slice(0, 8), [audits])

  async function loadPageData() {
    setLoading(true)
    try {
      const [configData, auditData] = await Promise.all([getSandboxConfigApi(), getSandboxAuditsApi()])
      setConfig(configData)
      setAudits(auditData)
      setStatus('')
      setLoadFailed(false)
    } catch (error) {
      setStatus(error instanceof Error ? `加载沙箱工作台失败：${error.message}` : '加载沙箱工作台失败')
      setLoadFailed(true)
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
            <h2>沙箱与审计</h2>
          </div>
          <button className="secondary-button" onClick={() => void loadPageData()} disabled={loading}>
            {loading ? '刷新中...' : '刷新'}
          </button>
        </div>
        {status ? <div className="page-banner info">{status}</div> : null}
        <div className="page-banner info">
          当前诊断主链并不依赖沙箱。这里主要查看少数受保护工具的执行边界和审计状态，不代表主流程必须经过沙箱。
        </div>
        <div className="summary-grid">
          {summaryItems.map((item) => (
            <article key={item.label} className="summary-card">
              <span>{item.label}</span>
              <strong>{item.value}</strong>
            </article>
          ))}
        </div>
      </section>

      {loadFailed ? (
        <section className="panel">
          <div className="empty-state">
            当前后端没有正确返回沙箱配置，所以这里先不展示占位数据。请重启后端后再刷新；如果仍失败，就继续查 `/api/sandbox/config`。
          </div>
        </section>
      ) : null}

      {!loadFailed && !config.enabled ? (
        <section className="panel">
          <div className="panel-header">
            <div>
              <h2>当前状态</h2>
              <p>当前诊断主链没有把沙箱作为必经执行层，所以这里不再展开复杂治理视图。</p>
            </div>
          </div>
          <div className="selector-list">
            <div className="status-item">
              <strong>当前没有作为主链启用</strong>
              <span>审批后端：{config.approvalBackend || '未配置'}</span>
              <span>默认 Profile：{config.defaultProfile.profileName || 'default'}</span>
              <span>文件系统：{formatBoolean(config.defaultProfile.readOnlyFilesystem, '只读', '可写')}</span>
              <span>网络：{formatBoolean(config.defaultProfile.networkEnabled, '开启', '关闭')}</span>
            </div>
            <div className="status-item">
              <strong>这页现在真正有用的信息</strong>
              <span>是否启用</span>
              <span>默认执行边界</span>
              <span>最近有没有真实审计记录</span>
            </div>
          </div>
        </section>
      ) : null}

      {!loadFailed && config.enabled ? (
      <section className="panel">
        <div className="panel-header">
          <div>
            <h2>辅助保护边界</h2>
            <p>这些边界只会影响少数受保护工具的执行，不是主诊断流程的必经环节。</p>
          </div>
        </div>
        <div className="detail-grid">
          <div>
            <strong>模式</strong>
            <p>{config.mode}</p>
          </div>
          <div>
            <strong>审批后端</strong>
            <p>{config.approvalBackend}</p>
          </div>
          <div>
            <strong>默认 Profile</strong>
            <p>{config.defaultProfile.profileName}</p>
          </div>
          <div>
            <strong>文件系统</strong>
            <p>{formatBoolean(config.defaultProfile.readOnlyFilesystem, '只读', '可写')}</p>
          </div>
          <div>
            <strong>网络</strong>
            <p>{formatBoolean(config.defaultProfile.networkEnabled, '开启', '关闭')}</p>
          </div>
          <div>
            <strong>超时时间</strong>
            <p>{config.defaultProfile.maxWallClockTime}</p>
          </div>
          <div>
            <strong>允许读取路径</strong>
            <p>{formatListPreview(config.defaultProfile.allowReadPaths)}</p>
          </div>
          <div>
            <strong>允许写入路径</strong>
            <p>{formatListPreview(config.defaultProfile.allowWritePaths)}</p>
          </div>
        </div>
      </section>
      ) : null}

      {!loadFailed ? (
      <section className="panel">
        <div className="panel-header">
          <div>
            <h2>最近审计</h2>
            <p>这里只看经过保护层的最近执行记录；没有记录，不代表主诊断流程没有运行。</p>
          </div>
        </div>
        <div className="selector-list">
          {latestAudits.length > 0 ? (
            latestAudits.map((audit) => (
              <div key={`${audit.executionId}-detail`} className="status-item">
                <strong>{audit.toolName}</strong>
                <span>时间：{formatTime(audit.createdAt)}</span>
                <span>目标：{`${audit.targetType}:${audit.targetName || '-'}`}</span>
                <span>权限决策：{audit.permissionDecision}</span>
                <span>执行结果：{describeAuditResult(audit)}</span>
                <span>审批状态：{audit.approvalStatus || audit.approvalBackend}</span>
              </div>
            ))
          ) : (
            <div className="empty-state compact">暂无审计记录。</div>
          )}
        </div>
      </section>
      ) : null}
    </div>
  )
}
