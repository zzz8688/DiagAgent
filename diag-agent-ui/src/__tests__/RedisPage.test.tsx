import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { RedisPage } from '../pages/RedisPage'

const getRedisOverviewApiMock = vi.fn()
const warmRedisHotKeysApiMock = vi.fn()

vi.mock('../api/index.ts', () => ({
  getRedisOverviewApi: (...args: unknown[]) => getRedisOverviewApiMock(...args),
  warmRedisHotKeysApi: (...args: unknown[]) => warmRedisHotKeysApiMock(...args),
}))

describe('RedisPage', () => {
  const warmupResult = { warmed: 1, touched: ['diag:hot:1'] }

  beforeEach(() => {
    vi.clearAllMocks()
    getRedisOverviewApiMock
      .mockResolvedValueOnce({
        redisAvailable: true,
        fallbackActive: false,
        knowledgeVersion: 'v1',
        activeSessionLocks: 1,
        activeTaskLocks: 2,
        rateLimitKeys: 3,
        generatingSessions: 4,
        cachedAnswerKeys: 5,
        bloomStats: [{ kind: 'answer', sampledSetBits: 10, nullCacheKeys: 1 }],
        hotKeys: [{ cacheKey: 'diag:hot:1', score: 88 }],
      })
      .mockResolvedValueOnce({
        redisAvailable: true,
        fallbackActive: true,
        knowledgeVersion: 'v2',
        activeSessionLocks: 1,
        activeTaskLocks: 2,
        rateLimitKeys: 3,
        generatingSessions: 4,
        cachedAnswerKeys: 5,
        bloomStats: [{ kind: 'answer', sampledSetBits: 12, nullCacheKeys: 2 }],
        hotKeys: [{ cacheKey: 'diag:hot:2', score: 99 }],
      })
    warmRedisHotKeysApiMock.mockResolvedValue(warmupResult)
  })

  it('Redis 页支持加载概览和预热热点', async () => {
    render(<RedisPage />)

    expect(await screen.findByText('Redis 面板')).toBeInTheDocument()
    expect(screen.getByText('关键能力')).toBeInTheDocument()
    expect(screen.getByText('Redis 在主链里怎么工作')).toBeInTheDocument()
    expect(screen.getByText('项目中的 Redis 能力')).toBeInTheDocument()
    expect(screen.getByText('Redis')).toBeInTheDocument()
    expect(screen.getByText('diag:hot:1')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '预热热点' }))

    const warmupStatus = `Redis 热点预热完成，成功预热 ${warmupResult.warmed} 个 key`
    expect(await screen.findByText(warmupStatus)).toBeInTheDocument()
    expect(screen.getByText('Redis 当前可访问，但系统里仍存在 fallback 状态，说明最近发生过降级或仍有本地兜底数据未清空。')).toBeInTheDocument()
    expect(screen.getByText('Redis + Fallback')).toBeInTheDocument()
    expect(screen.getByText('diag:hot:2')).toBeInTheDocument()
    expect(warmRedisHotKeysApiMock).toHaveBeenCalledWith(10)
  })
})
