import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { KnowledgePage } from '../pages/KnowledgePage'
import { getKnowledgeApi, getKnowledgeContentApi } from '../api/index.ts'
import { markdownToHtml } from '../utils/markdownToHtml.ts'

vi.mock('../api/index.ts', () => ({
  getKnowledgeApi: vi.fn(),
  getKnowledgeContentApi: vi.fn(),
  downloadKnowledgeApi: vi.fn(),
  reloadKnowledgeApi: vi.fn(),
  rebuildKnowledgeApi: vi.fn(),
  uploadKnowledgeApi: vi.fn(),
}))

vi.mock('../utils/markdownToHtml.ts', () => ({
  markdownToHtml: vi.fn(),
}))

const getKnowledgeApiMock = vi.mocked(getKnowledgeApi)
const getKnowledgeContentApiMock = vi.mocked(getKnowledgeContentApi)
const markdownToHtmlMock = vi.mocked(markdownToHtml)

const markdownDoc = {
  title: '操作手册',
  fileName: 'guide.md',
  type: 'MD',
  size: '1KB',
  updateTime: '2026-06-21 10:00:00',
}

describe('KnowledgePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getKnowledgeApiMock.mockResolvedValue({
      records: [markdownDoc],
      total: 1,
    })
  })

  it('Markdown 预览成功时展示转换后的 HTML', async () => {
    getKnowledgeContentApiMock.mockResolvedValue('# 标题')
    markdownToHtmlMock.mockResolvedValue('<h1>标题</h1>')

    const { container } = render(<KnowledgePage />)

    await waitFor(() => {
      expect(getKnowledgeApiMock).toHaveBeenCalled()
      expect(screen.getAllByRole('button', { name: '查看' }).length).toBeGreaterThan(0)
    })
    await userEvent.click(screen.getAllByRole('button', { name: '查看' })[0])

    expect(await screen.findByRole('button', { name: '关闭' })).toBeInTheDocument()
    expect(container.querySelector('.markdown-body')?.innerHTML).toContain('<h1>标题</h1>')
  })

  it('Markdown 预览失败时显示错误提示并清理预览弹窗', async () => {
    getKnowledgeContentApiMock.mockRejectedValue(new Error('读取失败'))

    render(<KnowledgePage />)

    await waitFor(() => {
      expect(getKnowledgeApiMock).toHaveBeenCalled()
      expect(screen.getAllByRole('button', { name: '查看' }).length).toBeGreaterThan(0)
    })
    await userEvent.click(screen.getAllByRole('button', { name: '查看' })[0])

    expect(await screen.findByText('读取知识内容失败：读取失败')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: '关闭' })).not.toBeInTheDocument()
    })
  })
})
