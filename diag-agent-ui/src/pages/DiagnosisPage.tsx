import { useEffect, useMemo, useRef, useState } from 'react'
import {
  clearAnswerCacheApi,
  createSessionApi,
  deleteSessionApi,
  diagnoseStream,
  getSessionMessagesApi,
  getSessionsApi,
  saveDiagnosisApi,
  stopGenerateApi,
  updateSessionApi,
} from '../api/index.ts'
import { MarkdownMessage } from '../components/MarkdownMessage.tsx'
import type { ChatMessageItem, EngineType, QuickQuestionItem, SessionSummary } from '../types'

const defaultQuestions: QuickQuestionItem[] = [
  { question: '结账失败了' },
  { question: '支付一直转圈' },
  { question: '商品页面打开很慢' },
  { question: '系统好像有点异常' },
  { question: '页面提示数据库连接超时' },
  { question: '凌晨偶尔会有订单失败' },
]

const CURRENT_ENGINE: EngineType = 'current-runtime'

function createSessionId(forceNew = false) {
  const key = 'diag_session_uuid_current_runtime'
  if (!forceNew) {
    const stored = localStorage.getItem(key)
    if (stored) {
      return stored
    }
  }
  const sessionId = `session-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
  localStorage.setItem(key, sessionId)
  return sessionId
}

function formatTime(value?: string) {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString('zh-CN', { hour12: false })
}

function buildTitle(content: string) {
  return content.length > 30 ? `${content.slice(0, 30)}...` : content
}

function detectLoopWarning(text?: string) {
  if (!text) {
    return ''
  }
  const normalized = text.toLowerCase()
  if (
    text.includes('最大轮次限制')
    || normalized.includes('max_turns_reached')
    || normalized.includes('max turns')
    || (normalized.includes('loop') && normalized.includes('停止'))
  ) {
    return '循环过多，已异常打断，请重试。'
  }
  return ''
}

function sanitizeAssistantContent(text?: string) {
  if (!text) {
    return ''
  }
  const withoutHtmlComments = text
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<!-[\s\S]*$/g, '')
    .replace(/<!--[\s\S]*$/g, '')
  const trimmed = withoutHtmlComments.trim()
  if (!trimmed) {
    return ''
  }
  if (
    trimmed === '诊断达到最大轮次限制，当前 loop 已被停止。'
    || trimmed === 'diagnosis stopped because max turns reached'
  ) {
    return ''
  }
  return withoutHtmlComments
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

export function DiagnosisPage() {
  const [query, setQuery] = useState('')
  const [sessionId, setSessionId] = useState('')
  const [messages, setMessages] = useState<ChatMessageItem[]>([])
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [quickQuestions, setQuickQuestions] = useState<QuickQuestionItem[]>(defaultQuestions)
  const [isLoading, setIsLoading] = useState(false)
  const [status, setStatus] = useState('')
  const [favoritedAssistantIds, setFavoritedAssistantIds] = useState<Set<string>>(new Set())
  const messageListRef = useRef<HTMLDivElement | null>(null)
  const activeAbortRef = useRef<null | (() => void)>(null)

  const orderedSessions = useMemo(
    () =>
      [...sessions].sort((left, right) => {
        const leftTime = left.updatedAt ? new Date(left.updatedAt).getTime() : 0
        const rightTime = right.updatedAt ? new Date(right.updatedAt).getTime() : 0
        return rightTime - leftTime
      }),
    [sessions],
  )
  useEffect(() => {
    setSessionId(createSessionId(true))
    void loadSessions()
    void loadQuickQuestions()
  }, [])

  useEffect(() => {
    if (!messageListRef.current) {
      return
    }
    messageListRef.current.scrollTop = messageListRef.current.scrollHeight
  }, [messages])

  async function loadSessions() {
    try {
      const data = await getSessionsApi()
      setSessions(data)
    } catch (error) {
      setStatus(error instanceof Error ? `加载会话失败：${error.message}` : '加载会话失败')
    }
  }

  async function loadQuickQuestions() {
    setQuickQuestions(defaultQuestions)
  }

  function resetConversation(forceNew = true) {
    setQuery('')
    setSessionId(createSessionId(forceNew))
    setMessages([])
    setFavoritedAssistantIds(new Set())
    setStatus('')
  }

  function updateMessage(id: string, updater: (message: ChatMessageItem) => ChatMessageItem) {
    setMessages((current) =>
      current.map((message) => (message.id === id ? updater(message) : message)),
    )
  }

  async function persistSession(currentSessionId: string, userText: string) {
    try {
      const exists = sessions.some((item) => item.sessionId === currentSessionId)
      if (exists) {
        await updateSessionApi(currentSessionId, { engine: CURRENT_ENGINE })
      } else {
        await createSessionApi({
          sessionId: currentSessionId,
          title: buildTitle(userText),
          engine: CURRENT_ENGINE,
        })
      }
      await loadSessions()
    } catch (error) {
      setStatus(error instanceof Error ? `保存会话失败：${error.message}` : '保存会话失败')
    }
  }

  async function selectSession(session: SessionSummary) {
    try {
      setStatus('正在加载会话...')
      const history = await getSessionMessagesApi(session.sessionId)
      setSessionId(session.sessionId)
      localStorage.setItem('diag_session_uuid_current_runtime', session.sessionId)
      setMessages(
        (Array.isArray(history) ? history : []).map((item, index) => {
          const rawText = item?.text || ''
          const isUser = String(item?.type || '').toUpperCase() === 'USER'
          const displayText = isUser ? rawText : sanitizeAssistantContent(rawText)
          return {
            id: `${session.sessionId}-${index}`,
            role: isUser ? 'user' : 'assistant',
            content: displayText,
            rawContent: rawText,
            loopWarning: isUser ? '' : detectLoopWarning(rawText),
            isTyping: false,
            isThinking: false,
            isStreaming: false,
          }
        }),
      )
      setFavoritedAssistantIds(new Set())
      setStatus(`已加载会话：${session.title || '未命名会话'}`)
    } catch (error) {
      setStatus(error instanceof Error ? `加载会话失败：${error.message}` : '加载会话失败')
    }
  }

  async function handleDeleteSession(targetSessionId: string) {
    if (!window.confirm('确定删除该会话吗？')) {
      return
    }
    try {
      await deleteSessionApi(targetSessionId)
      if (targetSessionId === sessionId) {
        resetConversation(true)
      }
      await loadSessions()
      setStatus('会话已删除')
    } catch (error) {
      setStatus(error instanceof Error ? `删除会话失败：${error.message}` : '删除会话失败')
    }
  }

  async function handleFavorite(index: number) {
    const userMessage = messages[index - 1]
    const assistantMessage = messages[index]
    if (
      !userMessage ||
      !assistantMessage ||
      userMessage.role !== 'user' ||
      assistantMessage.role !== 'assistant'
    ) {
      setStatus('当前消息不能收藏')
      return
    }
    if (favoritedAssistantIds.has(assistantMessage.id)) {
      setStatus('该问答已收藏')
      return
    }
    try {
      await saveDiagnosisApi({
        query: userMessage.content,
        conclusion: assistantMessage.content,
        engine: CURRENT_ENGINE,
        verified: false,
      })
      setFavoritedAssistantIds((current) => new Set(current).add(assistantMessage.id))
      setStatus('已加入收藏夹')
    } catch (error) {
      setStatus(error instanceof Error ? `收藏失败：${error.message}` : '收藏失败')
    }
  }

  async function handleStop() {
    activeAbortRef.current?.()
    activeAbortRef.current = null
    if (sessionId) {
      try {
        await stopGenerateApi(sessionId)
      } catch {
        // ignore stop errors after local abort
      }
    }
    setIsLoading(false)
    setMessages((current) =>
      current.map((message) =>
        message.role === 'assistant'
          ? { ...message, isThinking: false, isTyping: false, isStreaming: false }
          : message,
      ),
    )
    setStatus('已停止生成')
  }

  async function handleClearCache() {
    if (!window.confirm('确定清空热点答案缓存吗？这不会删除当前会话记录。')) {
      return
    }
    try {
      setStatus('正在清空热点缓存...')
      const result = await clearAnswerCacheApi()
      setStatus(result.message || '热点答案缓存已清空')
    } catch (error) {
      setStatus(error instanceof Error ? `清空缓存失败：${error.message}` : '清空缓存失败')
    }
  }

  async function runDiagnosis(userText: string) {
    const hasPersistedCurrentSession = sessionId ? sessions.some((item) => item.sessionId === sessionId) : false
    const shouldStartFreshSession = !sessionId || (messages.length === 0 && hasPersistedCurrentSession)
    const currentSessionId = shouldStartFreshSession ? createSessionId(true) : sessionId
    setSessionId(currentSessionId)
    const userMessageId = `${Date.now()}-user`
    const assistantMessageId = `${Date.now()}-assistant`
    setMessages((current) => [
      ...current,
      { id: userMessageId, role: 'user', content: userText },
      {
        id: assistantMessageId,
        role: 'assistant',
        content: '',
        rawContent: '',
        isThinking: true,
        isTyping: true,
        isStreaming: true,
      },
    ])
    setIsLoading(true)
    setStatus('正在诊断中...')

    const handle = diagnoseStream(userText, currentSessionId, (chunk) => {
      if (chunk === '[DONE]' || chunk === '[STOPPED]') {
        updateMessage(assistantMessageId, (message) => ({
          ...message,
          isThinking: false,
          isTyping: false,
          isStreaming: false,
        }))
        return
      }
      updateMessage(assistantMessageId, (message) => {
        const nextRawContent = `${message.rawContent ?? ''}${chunk}`
        return {
          ...message,
          rawContent: nextRawContent,
          content: sanitizeAssistantContent(nextRawContent),
          loopWarning: detectLoopWarning(nextRawContent),
          isThinking: false,
          isTyping: true,
          isStreaming: true,
        }
      })
    })
    activeAbortRef.current = handle.abort
    try {
      await handle.promise
      updateMessage(assistantMessageId, (message) => ({
        ...message,
        isThinking: false,
        isTyping: false,
        isStreaming: false,
      }))
      await persistSession(currentSessionId, userText)
      setStatus('诊断完成')
    } catch (error) {
      const message = error instanceof Error ? error.message : '诊断失败'
      if (message !== 'Aborted') {
        updateMessage(assistantMessageId, (current) => {
          const nextRawContent = current.rawContent || current.content || `请求失败：${message}`
          return {
            ...current,
            rawContent: nextRawContent,
            content: sanitizeAssistantContent(nextRawContent),
            loopWarning: detectLoopWarning(nextRawContent),
            isThinking: false,
            isTyping: false,
            isStreaming: false,
          }
        })
        setStatus(`诊断失败：${message}`)
      }
    } finally {
      activeAbortRef.current = null
      setIsLoading(false)
    }
  }

  async function handleSubmit() {
    const userText = query.trim()
    if (!userText) {
      setStatus('请输入问题描述')
      return
    }
    setQuery('')
    await runDiagnosis(userText)
  }

  return (
    <div className="diagnosis-page">
      <div className="diagnosis-main">
        <section className="panel">
          <div className="panel-header split">
            <div>
              <h2>智能诊断</h2>
              <p>统一使用当前主链运行时进行流式诊断</p>
            </div>
            <span className="engine-badge current-runtime">Current Runtime</span>
          </div>
          <div className="composer">
            <textarea
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              rows={4}
              placeholder="请描述您遇到的问题，例如：结账失败、支付超时、日志和指标相互矛盾..."
              disabled={isLoading}
            />
            <div className="toolbar-row">
              <button className="primary-button" onClick={() => void handleSubmit()} disabled={isLoading}>
                开始诊断
              </button>
              {isLoading ? (
                <button className="danger-button" onClick={() => void handleStop()}>
                  停止生成
                </button>
              ) : null}
              <button className="secondary-button" onClick={() => resetConversation(true)} disabled={isLoading}>
                新会话
              </button>
              <button
                className="secondary-button"
                onClick={() => resetConversation(true)}
                disabled={isLoading}
              >
                清空会话
              </button>
              <button
                className="secondary-button"
                onClick={() => void handleClearCache()}
                disabled={isLoading}
              >
                清空缓存
              </button>
            </div>
            {status ? <div className="page-banner info">{status}</div> : null}
          </div>
        </section>

        <section className="panel chat-panel">
          <div className="panel-header">
            <div>
              <h2>对话记录</h2>
              <p>这里只展示用户与助手消息。</p>
            </div>
          </div>
          <div className="chat-list" ref={messageListRef}>
            {messages.length === 0 ? (
              <div className="empty-state">{sessionId && sessions.some((item) => item.sessionId === sessionId) ? '暂无对话记录' : '开始一个新问题，诊断结果会在这里流式展示。'}</div>
            ) : null}
            {messages.map((message, index) => (
              <article key={message.id} className={`chat-item ${message.role}`}>
                <div className="chat-avatar">{message.role === 'user' ? '您' : 'AI'}</div>
                <div className="chat-bubble">
                  <div className="chat-meta">
                    <strong>{message.role === 'user' ? '用户' : '智能体'}</strong>
                    {message.role === 'assistant' ? (
                      <button className="link-button" onClick={() => void handleFavorite(index)}>
                        {favoritedAssistantIds.has(message.id) ? '已收藏' : '收藏'}
                      </button>
                    ) : null}
                  </div>
                  {message.role === 'assistant' ? (
                    message.content ? <MarkdownMessage markdown={message.content} streaming={Boolean(message.isStreaming)} /> : null
                  ) : (
                    <div className="plain-text">{message.content}</div>
                  )}
                  {message.isThinking ? <div className="typing-indicator">正在分析中...</div> : null}
                  {message.isTyping && !message.content ? (
                    <div className="typing-indicator">正在生成...</div>
                  ) : null}
                  {message.loopWarning ? <div className="chat-note">{message.loopWarning}</div> : null}
                </div>
              </article>
            ))}
          </div>
        </section>

        <section className="panel">
          <div className="panel-header">
            <div>
              <h2>常见问题</h2>
              <p>点击问题即可直接发起诊断。</p>
            </div>
          </div>
          <div className="quick-grid">
            {quickQuestions.map((item) => (
              <button
                key={item.question}
                className="tag-button"
                onClick={() => {
                  setQuery(item.question)
                  void runDiagnosis(item.question)
                }}
                disabled={isLoading}
              >
                <strong>{item.question}</strong>
              </button>
            ))}
          </div>
        </section>

      </div>

      <aside className="panel session-panel">
        <div className="panel-header split">
          <h2>会话列表</h2>
          <button className="secondary-button" onClick={() => void loadSessions()}>
            刷新
          </button>
        </div>
        <div className="session-groups">
          <div className="session-group-list">
            {orderedSessions.map((session) => (
              <div
                key={session.sessionId}
                className={`session-item ${session.sessionId === sessionId ? 'active' : ''}`}
              >
                <button
                  className="session-item-button"
                  onClick={() => void selectSession(session)}
                >
                  <span className="session-title">{session.title || '未命名会话'}</span>
                  <span className="session-time">{formatTime(session.updatedAt)}</span>
                </button>
                <button
                  className="session-delete-button"
                  onClick={() => void handleDeleteSession(session.sessionId)}
                >
                  删除
                </button>
              </div>
            ))}
            {orderedSessions.length === 0 ? <div className="empty-mini">暂无会话</div> : null}
          </div>
        </div>
      </aside>
    </div>
  )
}
