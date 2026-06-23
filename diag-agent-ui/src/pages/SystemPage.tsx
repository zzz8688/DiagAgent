import { useEffect, useState } from 'react'
import {
  getSystemHealthApi,
  getSystemTopologyApi,
} from '../api/index.ts'
import type { SystemHealthItem, TopologyData } from '../types'

const serviceFocusMap: Record<string, string> = {
  frontend: '关注首屏加载与登录态跳转是否阻塞。',
  'auth-service': '关注 token 校验、会话刷新和重定向循环。',
  'order-service': '关注跨服务串联稳定性和失败扩散面。',
  'product-service': '关注商品首屏、缓存命中和推荐依赖。',
  'payment-service': '关注重试耗尽、支付结果轮询和回执等待。',
  'recommendation-service': '关注推荐链路耗时和依赖抖动。',
  'promotion-service': '关注促销规则链路和凌晨窗口波动。',
  'gateway-service': '关注健康巡检、实例抖动和上游网关超时。',
  'external-payment-gateway': '关注外部支付通道 SLA 和错误码分布。',
  'inventory-db': '这里只保留库存基线，不作为默认主因。',
}

function getNodePosition(topology: TopologyData, nodeId: string, axis: 'x' | 'y') {
  return topology.nodes.find((node) => node.id === nodeId)?.[axis] ?? 0
}

function splitNodeLabel(label: string) {
  const normalized = label.trim()
  if (normalized.length <= 14 || !normalized.includes('-')) {
    return [normalized]
  }
  const segments = normalized.split('-').filter(Boolean)
  if (segments.length < 2) {
    return [normalized]
  }
  const midpoint = Math.ceil(segments.length / 2)
  return [
    segments.slice(0, midpoint).join('-'),
    segments.slice(midpoint).join('-'),
  ]
}

function summarizeHealth(healthItems: SystemHealthItem[]) {
  return healthItems.reduce(
    (summary, item) => {
      const normalizedStatus = item.status.toLowerCase()
      if (normalizedStatus === 'error') {
        summary.error += 1
      } else if (normalizedStatus === 'warning') {
        summary.warning += 1
      } else {
        summary.ok += 1
      }
      return summary
    },
    { ok: 0, warning: 0, error: 0 },
  )
}

export function SystemPage() {
  const [healthItems, setHealthItems] = useState<SystemHealthItem[]>([])
  const [topology, setTopology] = useState<TopologyData>({ nodes: [], links: [] })
  const [status, setStatus] = useState('')
  const [loading, setLoading] = useState(false)

  async function loadPageData() {
    setLoading(true)
    try {
      const [healthData, topologyData] = await Promise.all([
        getSystemHealthApi(),
        getSystemTopologyApi(),
      ])
      setHealthItems(healthData)
      setTopology(topologyData)
      setStatus('')
    } catch (error) {
      setStatus(error instanceof Error ? `加载系统状态失败：${error.message}` : '加载系统状态失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadPageData()
  }, [])

  const maxNodeX = Math.max(1040, ...topology.nodes.map(n => n.x + 130))
  const maxNodeY = Math.max(600, ...topology.nodes.map(n => n.y + 130))
  const healthSummary = summarizeHealth(healthItems)
  const primaryFocus = healthItems
    .filter((item) => item.status !== 'OK')
    .slice(0, 4)
    .map((item) => item.name)
    .join(' / ') || '当前未发现需要重点升级的问题面'

  return (
    <div className="page-stack">
      <section className="panel">
        <div className="panel-header split">
          <div>
            <h2>系统状态巡检</h2>
            <p>聚焦支付网关、商品首屏与缓存、鉴权会话、跨服务依赖，不再默认把异常收敛到数据库。</p>
          </div>
          <div className="toolbar-row">
            <button className="secondary-button" onClick={() => void loadPageData()} disabled={loading}>
              {loading ? '刷新中...' : '刷新'}
            </button>
          </div>
        </div>
        <div className="page-banner info">
          当前系统页展示的是多故障面巡检视角。重点先看网关抖动、健康巡检、首屏阻塞、缓存命中和会话鉴权，再决定是否需要继续下钻到数据库。
        </div>
        {status ? <div className="page-banner info">{status}</div> : null}
        <div className="summary-grid">
          <article className="summary-card">
            <span>巡检服务数</span>
            <strong>{healthItems.length}</strong>
          </article>
          <article className="summary-card">
            <span>告警分布</span>
            <strong>{healthSummary.error} Error / {healthSummary.warning} Warning</strong>
          </article>
          <article className="summary-card">
            <span>当前重点问题面</span>
            <strong>{primaryFocus}</strong>
          </article>
        </div>
      </section>

      <section className="panel">
        <div className="panel-header">
          <div>
            <h2>服务健康状态</h2>
            <p>每张卡片都对应当前最值得排查的故障面，帮助区分网关、缓存、鉴权和依赖波动。</p>
          </div>
        </div>
        <div className="health-grid">
          {healthItems.map((service) => (
            <article key={service.id} className="health-card">
              <div className="health-title">{service.name}</div>
              <span className={`health-badge ${service.status.toLowerCase()}`}>{service.status}</span>
              <p className="health-note">{serviceFocusMap[service.id] ?? '关注该服务最近的日志、指标和关联依赖。'}</p>
              <div className="metric-row">
                <span>CPU</span>
                <strong>{service.cpu}%</strong>
              </div>
              <div className="metric-row">
                <span>内存</span>
                <strong>{service.memory}%</strong>
              </div>
              <div className="metric-row">
                <span>延迟</span>
                <strong>{service.latency}ms</strong>
              </div>
              <div className="metric-row">
                <span>错误率</span>
                <strong>{service.errorRate}%</strong>
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className="panel">
        <div className="panel-header">
          <div>
            <h2>系统拓扑图</h2>
            <p>拓扑按当前真实问题面拆开展示，便于识别支付网关链路、商品依赖链路和跨服务摇摆。</p>
          </div>
        </div>
        <div className="page-banner info">
          这里把鉴权、推荐、促销、网关和库存基线拆开显示，避免所有异常都视觉上汇总到单一数据库节点。
        </div>
        <div className="topology-canvas">
          <svg width={maxNodeX} height={maxNodeY} viewBox={`0 0 ${maxNodeX} ${maxNodeY}`}>
            <defs>
              <marker
                id="arrowhead"
                markerWidth="10"
                markerHeight="7"
                refX="9"
                refY="3.5"
                orient="auto"
              >
                <polygon points="0 0, 10 3.5, 0 7" fill="#94a3b8" />
              </marker>
            </defs>
            {topology.links.map((link) => (
              <line
                key={`${link.source}-${link.target}`}
                x1={getNodePosition(topology, link.source, 'x')}
                y1={getNodePosition(topology, link.source, 'y')}
                x2={getNodePosition(topology, link.target, 'x')}
                y2={getNodePosition(topology, link.target, 'y')}
                stroke="#94a3b8"
                strokeWidth="2"
                markerEnd="url(#arrowhead)"
              />
            ))}
            {topology.nodes.map((node) => (
              <g key={node.id}>
                <circle cx={node.x} cy={node.y} r="58" fill="#2563eb" />
                <text x={node.x} y={node.y} textAnchor="middle" fill="#fff" fontSize="14" fontWeight="700">
                  {splitNodeLabel(node.name).map((line, index) => (
                    <tspan key={`${node.id}-${line}`} x={node.x} dy={index === 0 ? '-0.2em' : '1.2em'}>
                      {line}
                    </tspan>
                  ))}
                </text>
              </g>
            ))}
          </svg>
        </div>
      </section>

    </div>
  )
}
