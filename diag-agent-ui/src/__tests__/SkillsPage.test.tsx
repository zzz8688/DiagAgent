import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SkillsPage } from '../pages/SkillsPage'
import { getSkillContentApi, getSkillExecutionPlanApi, getSkillsApi } from '../api/index.ts'
import { markdownToHtml } from '../utils/markdownToHtml.ts'

vi.mock('../api/index.ts', () => ({
  downloadSkillApi: vi.fn(),
  getSkillContentApi: vi.fn(),
  getSkillExecutionPlanApi: vi.fn(),
  getSkillsApi: vi.fn(),
  uploadSkillApi: vi.fn(),
}))

vi.mock('../utils/markdownToHtml.ts', () => ({
  markdownToHtml: vi.fn(),
}))

const getSkillContentApiMock = vi.mocked(getSkillContentApi)
const getSkillExecutionPlanApiMock = vi.mocked(getSkillExecutionPlanApi)
const getSkillsApiMock = vi.mocked(getSkillsApi)
const markdownToHtmlMock = vi.mocked(markdownToHtml)

describe('SkillsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getSkillsApiMock.mockResolvedValue({
      records: [
        {
          skillId: 'skill-a',
          title: '技能A',
          type: 'MARKDOWN',
          source: 'BUNDLED',
          size: '1KB',
          updateTime: '2026-06-21 10:00:00',
        },
        {
          skillId: 'skill-b',
          title: '技能B',
          type: 'MARKDOWN',
          source: 'FILESYSTEM',
          size: '1KB',
          updateTime: '2026-06-21 10:05:00',
        },
      ],
      total: 2,
    })
  })

  it('技能正文读取失败后仍可关闭并查看其他技能', async () => {
    getSkillContentApiMock.mockRejectedValueOnce(new Error('正文失败')).mockResolvedValueOnce('# skill-b')
    markdownToHtmlMock.mockResolvedValue('<h1>skill-b</h1>')

    const { container } = render(<SkillsPage />)

    expect(await screen.findByText('技能A')).toBeInTheDocument()
    const viewButtons = await screen.findAllByRole('button', { name: '查看' })
    await userEvent.click(viewButtons[0])

    expect(await screen.findByText('读取技能正文失败：正文失败')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: '关闭' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '关闭' }))
    expect(screen.queryByRole('heading', { name: '技能A' })).not.toBeInTheDocument()

    await userEvent.click(screen.getAllByRole('button', { name: '查看' })[1])

    expect(await screen.findByRole('heading', { name: '技能B' })).toBeInTheDocument()
    await waitFor(() => {
      expect(container.querySelector('.markdown-body')?.innerHTML).toContain('<h1>skill-b</h1>')
    })
  })

  it('执行计划读取失败后仍可切换到其他技能继续查看', async () => {
    getSkillExecutionPlanApiMock
      .mockRejectedValueOnce(new Error('执行计划失败'))
      .mockResolvedValueOnce({
        mode: 'tool',
        summary: '可执行',
        argumentsSchema: { type: 'object' },
      })

    render(<SkillsPage />)

    expect(await screen.findByText('技能A')).toBeInTheDocument()
    const planButtons = await screen.findAllByRole('button', { name: '执行计划' })
    await userEvent.click(planButtons[0])

    expect(await screen.findByText('读取执行计划失败：执行计划失败')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: '技能A · 执行计划' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '关闭' }))
    expect(screen.queryByRole('heading', { name: '技能A · 执行计划' })).not.toBeInTheDocument()

    await userEvent.click(screen.getAllByRole('button', { name: '执行计划' })[1])

    expect(await screen.findByRole('heading', { name: '技能B · 执行计划' })).toBeInTheDocument()
    expect(screen.getByText(/"summary": "可执行"/)).toBeInTheDocument()
  })
})
