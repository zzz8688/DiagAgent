import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HistoryPage } from '../pages/HistoryPage'
import { deleteAllHistoryApi, deleteHistoryApi, getHistoryPageApi } from '../api/index.ts'

vi.mock('../api/index.ts', () => ({
  getHistoryPageApi: vi.fn(),
  deleteHistoryApi: vi.fn(),
  deleteAllHistoryApi: vi.fn(),
}))

const getHistoryPageApiMock = vi.mocked(getHistoryPageApi)
const deleteHistoryApiMock = vi.mocked(deleteHistoryApi)
const deleteAllHistoryApiMock = vi.mocked(deleteAllHistoryApi)

const sampleRecord = {
  id: 1,
  query: '支付失败',
  conclusion: '请检查支付网关',
  engine: 'llm',
  verified: false,
  createdAt: '2026-06-21 10:00:00',
}

describe('HistoryPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('confirm', vi.fn(() => true))
    getHistoryPageApiMock.mockResolvedValue({
      records: [sampleRecord],
      total: 1,
    })
  })

  it('单条删除失败时保留原列表并显示错误提示', async () => {
    deleteHistoryApiMock.mockRejectedValue(new Error('服务异常'))

    render(<HistoryPage />)

    expect(await screen.findByText('支付失败')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '取消收藏' }))

    expect(await screen.findByText('取消收藏失败：服务异常')).toBeInTheDocument()
    expect(screen.getByText('支付失败')).toBeInTheDocument()
    expect(deleteHistoryApiMock).toHaveBeenCalledWith(1)
  })

  it('全部删除失败时不重置现有分页内容', async () => {
    deleteAllHistoryApiMock.mockRejectedValue(new Error('批量删除失败'))

    render(<HistoryPage />)

    expect(await screen.findByText('支付失败')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '全部取消收藏' }))

    expect(await screen.findByText('清空收藏夹失败：批量删除失败')).toBeInTheDocument()
    expect(screen.getByText('支付失败')).toBeInTheDocument()
    await waitFor(() => {
      expect(getHistoryPageApiMock).toHaveBeenCalledTimes(1)
    })
  })

  it('收藏夹页面不再展示引擎列', async () => {
    render(<HistoryPage />)

    expect(await screen.findByText('支付失败')).toBeInTheDocument()
    expect(screen.queryByText('引擎')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '查看' }))
    expect(screen.queryByText('诊断引擎')).not.toBeInTheDocument()
  })
})
