import { useEffect, useState } from 'react'
import { getRedisOverviewApi, warmRedisHotKeysApi } from '../api/index.ts'
import type { RedisOverviewData, RedisWarmupResultData } from '../types'

type CapabilityCard = {
  title: string
  metric: string
  whyItMatters: string
  projectValue: string
}

type StepCard = {
  title: string
  description: string
}

function buildCapabilityCards(redisOverview: RedisOverviewData | null): CapabilityCard[] {
  if (!redisOverview) {
    return []
  }
  return [
    {
      title: '缓存复用',
      metric: `${redisOverview.cachedAnswerKeys} 个答案缓存 key`,
      whyItMatters: '这里重点体现缓存命中、热点预热和结果复用。先命中缓存，再决定要不要回源生成。',
      projectValue: '这里体现的是“本地缓存 + Redis 缓存 + 热点预热”三层复用，减少重复调用模型、日志和指标工具。',
    },
    {
      title: '分布式锁与并发保护',
      metric: `${redisOverview.activeSessionLocks + redisOverview.activeTaskLocks} 个活跃锁`,
      whyItMatters: '这里重点体现 Redis 锁、运行态协调和防重入思路。',
      projectValue: '这里体现的是“同一 session 不重复生成、同一 task 不重复执行”，比只展示加锁更接近真实工程治理。',
    },
    {
      title: '布隆与空值保护',
      metric: `${redisOverview.bloomStats.reduce((sum, item) => sum + item.nullCacheKeys, 0)} 个空值缓存`,
      whyItMatters: '这条线是缓存穿透治理的工程落地版，对 sessionId、taskId 运行标识进行保护。',
      projectValue: '这里体现的是“Bloom + 空值缓存”已经用在身份守卫和无效请求过滤上，而不是停留在概念层。',
    },
    {
      title: '限流与系统自保护',
      metric: `${redisOverview.rateLimitKeys} 个限流窗口`,
      whyItMatters: '这条线重点体现系统治理思路。不是只提升性能，而是先保证系统在高峰时不把自己压垮。',
      projectValue: '这里体现的是“入口限流 + 生成中状态保护 + 运行态协调”是一整条防重入、防打爆链路。',
    },
  ]
}

function buildRuntimeSteps(): StepCard[] {
  return [
    {
      title: '1. 先判断能不能放行',
      description: '请求进入控制器后，先经过限流窗口、生成中会话保护和分布式锁，而不是直接调用模型。',
    },
    {
      title: '2. 再判断能不能复用',
      description: '如果命中本地缓存或 Redis 答案缓存，就直接返回已有结果，减少重复跑完整诊断链路。',
    },
    {
      title: '3. 未命中才进入主链',
      description: '缓存未命中时，Redis 继续承担 session 锁、task 锁和生成中状态，保证 ReAct loop 不重入。',
    },
    {
      title: '4. 结束后回写缓存',
      description: '诊断完成后，结果会回写缓存和热点统计；知识版本更新时再联动清理，形成闭环。',
    },
  ]
}

function buildProjectHighlights(): StepCard[] {
  return [
    {
      title: '缓存层',
      description: '缓存命中、热点预热、空值缓存、缓存与主流程配合。',
    },
    {
      title: '协调层',
      description: '分布式锁、限流、并发保护和系统自保护。',
    },
    {
      title: 'DiagAgent 里的真实角色',
      description: 'Redis 在这里既是缓存层，也是运行时协调层，负责结果复用、并发控制、身份守卫和系统保护。',
    },
  ]
}

export function RedisPage() {
  const [redisOverview, setRedisOverview] = useState<RedisOverviewData | null>(null)
  const [warmupResult, setWarmupResult] = useState<RedisWarmupResultData | null>(null)
  const [status, setStatus] = useState('')
  const [loading, setLoading] = useState(false)

  const capabilityCards = buildCapabilityCards(redisOverview)
  const runtimeSteps = buildRuntimeSteps()
  const projectHighlights = buildProjectHighlights()

  async function loadPageData() {
    setLoading(true)
    try {
      setRedisOverview(await getRedisOverviewApi())
      setStatus('')
    } catch (error) {
      setStatus(error instanceof Error ? `加载 Redis 页面失败：${error.message}` : '加载 Redis 页面失败')
    } finally {
      setLoading(false)
    }
  }

  async function handleRedisWarmup() {
    try {
      const result = await warmRedisHotKeysApi(10)
      setWarmupResult(result)
      setStatus(`Redis 热点预热完成，成功预热 ${result.warmed} 个 key`)
      setRedisOverview(await getRedisOverviewApi())
    } catch (error) {
      setStatus(error instanceof Error ? `Redis 预热失败：${error.message}` : 'Redis 预热失败')
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
            <h2>Redis 面板</h2>    
          </div>
          <div className="toolbar-row">
            <button className="secondary-button" onClick={() => void loadPageData()} disabled={loading}>
              {loading ? '刷新中...' : '刷新'}
            </button>
            <button className="secondary-button" onClick={() => void handleRedisWarmup()}>
              预热热点
            </button>
          </div>
        </div>
        {status ? <div className="page-banner info">{status}</div> : null}
        <div className="page-banner info">
          这里重点看 4 条主线：缓存复用、分布式锁、限流保护、布隆与空值缓存。
        </div>
        {redisOverview ? (
          <>
            {!redisOverview.redisAvailable ? (
              <div className="page-banner warning">
                Redis 当前不可用，页面统计可能已经降级为本地 fallback 视角，不能按“0 就是没有”来理解。
              </div>
            ) : null}
            {redisOverview.redisAvailable && redisOverview.fallbackActive ? (
              <div className="page-banner warning">
                Redis 当前可访问，但系统里仍存在 fallback 状态，说明最近发生过降级或仍有本地兜底数据未清空。
              </div>
            ) : null}
            <div className="health-grid">
              <article className="health-card">
                <div className="health-title">Redis 状态</div>
                <div className="metric-row">
                  <span>当前模式</span>
                  <strong>
                    {!redisOverview.redisAvailable
                      ? 'Fallback'
                      : redisOverview.fallbackActive
                        ? 'Redis + Fallback'
                        : 'Redis'}
                  </strong>
                </div>
              </article>
              <article className="health-card">
                <div className="health-title">知识版本</div>
                <div className="summary-code">{redisOverview.knowledgeVersion}</div>
              </article>
              <article className="health-card">
                <div className="health-title">答案缓存</div>
                <div className="metric-row">
                  <span>缓存 key</span>
                  <strong>{redisOverview.cachedAnswerKeys}</strong>
                </div>
              </article>
              <article className="health-card">
                <div className="health-title">会话主锁</div>
                <div className="metric-row">
                  <span>活跃锁</span>
                  <strong>{redisOverview.activeSessionLocks}</strong>
                </div>
              </article>
              <article className="health-card">
                <div className="health-title">任务锁</div>
                <div className="metric-row">
                  <span>活跃锁</span>
                  <strong>{redisOverview.activeTaskLocks}</strong>
                </div>
              </article>
              <article className="health-card">
                <div className="health-title">限流窗口</div>
                <div className="metric-row">
                  <span>活跃 key</span>
                  <strong>{redisOverview.rateLimitKeys}</strong>
                </div>
              </article>
              <article className="health-card">
                <div className="health-title">生成中会话</div>
                <div className="metric-row">
                  <span>当前数量</span>
                  <strong>{redisOverview.generatingSessions}</strong>
                </div>
              </article>
            </div>
            <div className="detail-grid" style={{ marginTop: 16 }}>
              <div className="detail-full">
                <strong>关键能力</strong>
                <div className="status-grid" style={{ marginTop: 12 }}>
                  {capabilityCards.map((item) => (
                    <article key={item.title} className="status-card">
                      <h3>{item.title}</h3>
                      <div className="metric-row">
                        <span>当前指标</span>
                        <strong>{item.metric}</strong>
                      </div>
                      <p>{item.whyItMatters}</p>
                      <div className="empty-mini">{item.projectValue}</div>
                    </article>
                  ))}
                </div>
              </div>
            </div>
            <div className="detail-grid" style={{ marginTop: 16 }}>
              <div>
                <strong>布隆与空值缓存</strong>
                <div className="selector-list" style={{ marginTop: 12 }}>
                  {redisOverview.bloomStats.map((item) => (
                    <div key={item.kind} className="status-item">
                      <strong>{item.kind}</strong>
                      <span>已采样 bit：{item.sampledSetBits}</span>
                      <span>空值缓存：{item.nullCacheKeys}</span>
                    </div>
                  ))}
                </div>
              </div>
              <div>
                <strong>热点 Key Top 10</strong>
                <div className="selector-list" style={{ marginTop: 12 }}>
                  {redisOverview.hotKeys.length > 0 ? (
                    redisOverview.hotKeys.map((item) => (
                      <div key={item.cacheKey} className="status-item">
                        <strong className="summary-code">{item.cacheKey}</strong>
                        <span>热度分：{item.score.toFixed(0)}</span>
                      </div>
                    ))
                  ) : (
                    <div className="empty-state compact">暂无热点 key 统计</div>
                  )}
                </div>
              </div>
            </div>
            <div className="detail-grid" style={{ marginTop: 16 }}>
              <div className="detail-full">
                <strong>Redis 在主链里怎么工作</strong>
                <div className="selector-list" style={{ marginTop: 12 }}>
                  {runtimeSteps.map((step) => (
                    <div key={step.title} className="status-item">
                      <strong>{step.title}</strong>
                      <span>{step.description}</span>
                    </div>
                  ))}
                </div>
              </div>
              <div className="detail-full">
                <strong>项目中的 Redis 能力</strong>
                <div className="selector-list" style={{ marginTop: 12 }}>
                  {projectHighlights.map((item) => (
                    <div key={item.title} className="status-item">
                      <strong>{item.title}</strong>
                      <span>{item.description}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
            {warmupResult ? (
              <div className="detail-grid" style={{ marginTop: 16 }}>
                <div className="detail-full">
                  <strong>最近一次热点预热结果</strong>
                  <pre className="json-block">{JSON.stringify(warmupResult, null, 2)}</pre>
                </div>
              </div>
            ) : null}
          </>
        ) : (
          <div className="empty-state compact">Redis 面板数据加载中</div>
        )}
      </section>
    </div>
  )
}
