import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SystemPage } from '../pages/SystemPage'

vi.mock('../api/index.ts', () => ({
  getSystemHealthApi: vi.fn(() => Promise.resolve([
    { id: 'gateway-service', name: 'Gateway Service', status: 'Error', cpu: 58, memory: 36, latency: 2710, errorRate: 14 },
    { id: 'product-service', name: 'Product Service', status: 'Warning', cpu: 54, memory: 45, latency: 2160, errorRate: 5 },
  ])),
  getSystemTopologyApi: vi.fn(() => Promise.resolve({
    nodes: [
      { id: 'frontend', name: 'Frontend', status: 'OK', x: 100, y: 100 },
      { id: 'gateway-service', name: 'Gateway Service', status: 'Error', x: 260, y: 260 },
    ],
    links: [{ source: 'frontend', target: 'gateway-service' }],
  })),
}))

describe('SystemPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('系统页改为多故障面巡检视角', async () => {
    render(<SystemPage />)

    expect(await screen.findByText('系统状态巡检')).toBeInTheDocument()
    expect(screen.getByText('服务健康状态')).toBeInTheDocument()
    expect(screen.getByText('系统拓扑图')).toBeInTheDocument()
    expect(screen.getByText('当前重点问题面')).toBeInTheDocument()
    expect(screen.getByText('Gateway Service / Product Service')).toBeInTheDocument()
    expect(screen.getByText('1 Error / 1 Warning')).toBeInTheDocument()
    expect(screen.getByText('关注健康巡检、实例抖动和上游网关超时。')).toBeInTheDocument()
    expect(screen.queryByText('通知联调结果')).not.toBeInTheDocument()
    expect(screen.queryByText('Runtime Metrics')).not.toBeInTheDocument()
    expect(screen.queryByText('Redis 面板')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '测试通知' })).not.toBeInTheDocument()
  })
})
