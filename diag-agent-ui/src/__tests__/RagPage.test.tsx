import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { RagPage } from '../pages/RagPage'
import { evaluateRagApi, getRagGovernanceApi, rebuildKnowledgeApi } from '../api/index.ts'

vi.mock('../api/index.ts', () => ({
  evaluateRagApi: vi.fn(),
  getRagGovernanceApi: vi.fn(),
  rebuildKnowledgeApi: vi.fn(),
}))

const evaluateRagApiMock = vi.mocked(evaluateRagApi)
const getRagGovernanceApiMock = vi.mocked(getRagGovernanceApi)
const rebuildKnowledgeApiMock = vi.mocked(rebuildKnowledgeApi)

describe('RagPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    rebuildKnowledgeApiMock.mockResolvedValue('知识库全量重建成功')
    getRagGovernanceApiMock.mockResolvedValue({
      activeConfig: {
        vectorTopK: 8,
        keywordTopK: 8,
        finalTopK: 5,
        fusionTopK: 10,
        rerankTopN: 8,
        minVectorScore: 0.2,
        rerankerConfigured: true,
      },
      baselineSuites: [
        {
          id: 'suite-1',
          displayName: '基础套件',
          description: 'baseline',
          cases: [{ query: '排查 502', expectedKeywords: ['502'] }],
        },
      ],
      configPresets: [
        {
          id: 'active-config',
          displayName: '当前配置',
          description: 'preset',
          configOverride: { finalTopK: 5 },
        },
      ],
      latestEvaluation: {
        evaluationId: 'eval-1',
        evaluationLabel: '最近评测',
        evaluatedAt: '2026-06-21T10:00:00Z',
        baselineSuiteId: 'suite-1',
        presetId: 'active-config',
        summary: {
          hitAt1Ratio: 0.5,
          hitAt1Count: 1,
          hitAt3Ratio: 1,
          hitAt3Count: 2,
          meanReciprocalRank: 0.75,
          totalCases: 2,
        },
        effectiveConfig: { finalTopK: 5 },
        cases: [],
      },
      recentEvaluations: [
        {
          evaluationId: 'eval-1',
          evaluationLabel: '最近评测',
          evaluatedAt: '2026-06-21T10:00:00Z',
          baselineSuiteId: 'suite-1',
          presetId: 'active-config',
          summary: {
            hitAt1Ratio: 0.5,
            hitAt3Ratio: 1,
            meanReciprocalRank: 0.75,
            totalCases: 2,
          },
          effectiveConfig: { finalTopK: 5 },
        },
      ],
    } as never)
  })

  it('非法 RAG 评测输入会显示失败提示且保留原治理数据', async () => {
    render(<RagPage />)

    expect((await screen.findAllByText((content) => content.includes('最近评测'))).length).toBeGreaterThan(0)
    const textarea = screen.getByPlaceholderText('输入 RAG 评测请求 JSON')

    fireEvent.change(textarea, { target: { value: '{bad json' } })
    await userEvent.click(screen.getByRole('button', { name: '运行评测' }))

    expect(await screen.findByText(/RAG 评测失败/)).toBeInTheDocument()
    expect(evaluateRagApiMock).not.toHaveBeenCalled()
    expect(getRagGovernanceApiMock).toHaveBeenCalledTimes(1)
    expect(screen.getAllByText((content) => content.includes('最近评测')).length).toBeGreaterThan(0)
    expect(screen.getByText('50.0%')).toBeInTheDocument()
  })
})
