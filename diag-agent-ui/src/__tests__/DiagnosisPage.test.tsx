import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DiagnosisPage } from '../pages/DiagnosisPage'
import {
  clearAnswerCacheApi,
  createSessionApi,
  deleteSessionApi,
  diagnoseStream,
  getSessionMessagesApi,
  getSessionsApi,
  updateSessionApi,
} from '../api/index.ts'

vi.mock('../components/MarkdownMessage.tsx', () => ({
  MarkdownMessage: ({ markdown }: { markdown: string }) => <div>{markdown}</div>,
}))

vi.mock('../api/index.ts', () => ({
  clearAnswerCacheApi: vi.fn(),
  createSessionApi: vi.fn(),
  deleteSessionApi: vi.fn(),
  diagnoseStream: vi.fn(),
  getSessionMessagesApi: vi.fn(),
  getSessionsApi: vi.fn(),
  saveDiagnosisApi: vi.fn(),
  stopGenerateApi: vi.fn(),
  updateSessionApi: vi.fn(),
}))

const clearAnswerCacheApiMock = vi.mocked(clearAnswerCacheApi)
const createSessionApiMock = vi.mocked(createSessionApi)
const deleteSessionApiMock = vi.mocked(deleteSessionApi)
const diagnoseStreamMock = vi.mocked(diagnoseStream)
const getSessionMessagesApiMock = vi.mocked(getSessionMessagesApi)
const getSessionsApiMock = vi.mocked(getSessionsApi)
const updateSessionApiMock = vi.mocked(updateSessionApi)

describe('DiagnosisPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('confirm', vi.fn(() => true))
    localStorage.clear()
    getSessionsApiMock
      .mockResolvedValueOnce([
        {
          id: 1,
          sessionId: 'session-old',
          title: '旧会话',
          updatedAt: '2026-06-21T10:00:00Z',
          engine: 'llm',
        },
      ])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          id: 2,
          sessionId: 'session-new',
          title: '新的诊断问题',
          updatedAt: '2026-06-21T10:05:00Z',
          engine: 'llm',
        },
      ])
    getSessionMessagesApiMock.mockResolvedValue([
      { type: 'USER', text: '旧问题' },
      { type: 'AI', text: '旧结论' },
    ])
    deleteSessionApiMock.mockResolvedValue()
    clearAnswerCacheApiMock.mockResolvedValue({ message: '热点答案缓存已清空' })
    createSessionApiMock.mockResolvedValue()
    updateSessionApiMock.mockResolvedValue()
    diagnoseStreamMock.mockImplementation((_query, _sessionId, onChunk) => ({
      abort: vi.fn(),
      promise: Promise.resolve().then(() => {
        onChunk('新的回答')
        onChunk('[DONE]')
        return '新的回答'
      }),
    }))
  })

  it('删除当前会话后可以创建新会话继续诊断', async () => {
    getSessionsApiMock.mockReset()
    getSessionsApiMock
      .mockResolvedValueOnce([
        {
          id: 1,
          sessionId: 'session-old',
          title: '旧会话',
          updatedAt: '2026-06-21T10:00:00Z',
          engine: 'llm',
        },
      ])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          id: 2,
          sessionId: 'session-new',
          title: '新的诊断问题',
          updatedAt: '2026-06-21T10:05:00Z',
          engine: 'llm',
        },
      ])
    getSessionMessagesApiMock.mockResolvedValueOnce([
      { type: 'USER', text: '旧问题' },
      { type: 'AI', text: '旧结论' },
    ])

    render(<DiagnosisPage />)

    const oldSessionButton = await screen.findByRole('button', { name: /旧会话/ })
    await userEvent.click(oldSessionButton)

    expect(await screen.findByText('已加载会话：旧会话')).toBeInTheDocument()

    const sessionItem = oldSessionButton.closest('.session-item')
    expect(sessionItem).not.toBeNull()
    await userEvent.click(within(sessionItem as HTMLElement).getByText('删除'))

    expect(await screen.findByText('会话已删除')).toBeInTheDocument()
    expect(screen.getByText('开始一个新问题，诊断结果会在这里流式展示。')).toBeInTheDocument()
    expect(screen.queryByText('旧问题')).not.toBeInTheDocument()
    expect(screen.queryByText('旧会话')).not.toBeInTheDocument()

    await userEvent.type(screen.getByPlaceholderText(/请描述您遇到的问题/), '新的诊断问题')
    await userEvent.click(screen.getByRole('button', { name: '开始诊断' }))

    expect(await screen.findByText('诊断完成')).toBeInTheDocument()
    expect(screen.getAllByText('新的诊断问题').length).toBeGreaterThan(0)
    expect(screen.getByText('新的回答')).toBeInTheDocument()
    expect(createSessionApiMock).toHaveBeenCalledTimes(1)
    expect(createSessionApiMock.mock.calls[0]?.[0]).toMatchObject({
      title: '新的诊断问题',
      engine: 'current-runtime',
    })
    expect(createSessionApiMock.mock.calls[0]?.[0].sessionId).not.toBe('session-old')
  })

  it('已存在会话没有历史消息时显示空对话提示', async () => {
    getSessionsApiMock.mockReset()
    getSessionsApiMock.mockResolvedValueOnce([
      {
        id: 1,
        sessionId: 'session-old',
        title: '旧会话',
        updatedAt: '2026-06-21T10:00:00Z',
        engine: 'llm',
      },
    ])
    getSessionMessagesApiMock.mockResolvedValueOnce([])

    render(<DiagnosisPage />)

    const oldSessionButton = await screen.findByRole('button', { name: /旧会话/ })
    await userEvent.click(oldSessionButton)

    expect(await screen.findByText('已加载会话：旧会话')).toBeInTheDocument()
    expect(screen.getByText('暂无对话记录')).toBeInTheDocument()
  })

  it('清空会话后再次诊断会创建新的 sessionId', async () => {
    getSessionsApiMock.mockReset()
    getSessionsApiMock
      .mockResolvedValueOnce([
        {
          id: 1,
          sessionId: 'session-old',
          title: '旧会话',
          updatedAt: '2026-06-21T10:00:00Z',
          engine: 'llm',
        },
      ])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          id: 2,
          sessionId: 'session-new',
          title: '清空后新问题',
          updatedAt: '2026-06-21T10:05:00Z',
          engine: 'llm',
        },
      ])
    getSessionMessagesApiMock.mockResolvedValueOnce([
      { type: 'USER', text: '旧问题' },
      { type: 'AI', text: '旧结论' },
    ])

    render(<DiagnosisPage />)

    const oldSessionButton = await screen.findByRole('button', { name: /旧会话/ })
    await userEvent.click(oldSessionButton)

    await userEvent.click(screen.getByRole('button', { name: '清空会话' }))
    expect(screen.getByText('开始一个新问题，诊断结果会在这里流式展示。')).toBeInTheDocument()

    await userEvent.type(screen.getByPlaceholderText(/请描述您遇到的问题/), '清空后新问题')
    await userEvent.click(screen.getByRole('button', { name: '开始诊断' }))

    expect(await screen.findByText('诊断完成')).toBeInTheDocument()
    expect(createSessionApiMock).toHaveBeenCalledTimes(1)
    expect(createSessionApiMock.mock.calls[0]?.[0]).toMatchObject({
      title: '清空后新问题',
      engine: 'current-runtime',
    })
    expect(createSessionApiMock.mock.calls[0]?.[0].sessionId).not.toBe('session-old')
  })

  it('可以在智能诊断页清空热点答案缓存', async () => {
    render(<DiagnosisPage />)

    await userEvent.click(await screen.findByRole('button', { name: '清空缓存' }))

    expect(clearAnswerCacheApiMock).toHaveBeenCalledTimes(1)
    expect(await screen.findByText('热点答案缓存已清空')).toBeInTheDocument()
  })

  it('常见问题只展示简短问题文本', async () => {
    render(<DiagnosisPage />)

    expect(await screen.findByText('点击问题即可直接发起诊断。')).toBeInTheDocument()
    expect(screen.getByText('结账失败了')).toBeInTheDocument()
    expect(screen.getByText('支付一直转圈')).toBeInTheDocument()
    expect(screen.getByText('商品页面打开很慢')).toBeInTheDocument()
    expect(screen.getByText('系统好像有点异常')).toBeInTheDocument()
    expect(screen.getByText('页面提示数据库连接超时')).toBeInTheDocument()
    expect(screen.getByText('凌晨偶尔会有订单失败')).toBeInTheDocument()
    expect(screen.queryByText(/证据侧重点：/)).not.toBeInTheDocument()
    expect(screen.queryByText(/推荐 skill：/)).not.toBeInTheDocument()
    expect(screen.queryByText(/人工介入/)).not.toBeInTheDocument()
  })

  it('超过最大轮次时在对话里显示异常打断小字提示', async () => {
    diagnoseStreamMock.mockImplementationOnce((_query, _sessionId, onChunk) => ({
      abort: vi.fn(),
      promise: Promise.resolve().then(() => {
        onChunk('诊断达到最大轮次限制，当前 loop 已被停止。')
        onChunk('[DONE]')
        return '诊断达到最大轮次限制，当前 loop 已被停止。'
      }),
    }))

    render(<DiagnosisPage />)

    await userEvent.type(screen.getByPlaceholderText(/请描述您遇到的问题/), '帮我诊断一个复杂问题')
    await userEvent.click(screen.getByRole('button', { name: '开始诊断' }))

    expect(await screen.findByText('循环过多，已异常打断，请重试。')).toBeInTheDocument()
    expect(screen.queryByText('诊断达到最大轮次限制，当前 loop 已被停止。')).not.toBeInTheDocument()
  })

  it('流式回答中的模板注释不会直接泄露到页面上', async () => {
    diagnoseStreamMock.mockImplementationOnce((_query, _sessionId, onChunk) => ({
      abort: vi.fn(),
      promise: Promise.resolve().then(() => {
        onChunk('### 当前判断\n支付链路存在延迟\n<!-- - 当前事实来源：queryLogs -->\n### 建议\n继续补 trace')
        onChunk('[DONE]')
        return 'done'
      }),
    }))

    render(<DiagnosisPage />)

    await userEvent.type(screen.getByPlaceholderText(/请描述您遇到的问题/), '支付一直转圈')
    await userEvent.click(screen.getByRole('button', { name: '开始诊断' }))

    expect(await screen.findByText(/支付链路存在延迟/)).toBeInTheDocument()
    expect(screen.queryByText(/当前事实来源/)).not.toBeInTheDocument()
    expect(screen.queryByText(/<!--/)).not.toBeInTheDocument()
  })
})
