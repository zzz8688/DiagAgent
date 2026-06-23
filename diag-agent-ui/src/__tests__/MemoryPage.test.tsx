import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryPage } from '../pages/MemoryPage'
import {
  getMemoryFileContentApi,
  getMemoryOverviewApi,
  getMemoryVersionDiffApi,
  getMemoryVersionsApi,
  rollbackMemoryVersionApi,
} from '../api/index.ts'

vi.mock('../api/index.ts', () => ({
  getMemoryFileContentApi: vi.fn(),
  getMemoryOverviewApi: vi.fn(),
  getMemoryVersionDiffApi: vi.fn(),
  getMemoryVersionsApi: vi.fn(),
  reloadMemoryIndexApi: vi.fn(),
  rollbackMemoryVersionApi: vi.fn(),
  runMemoryMaintenanceApi: vi.fn(),
}))

const getMemoryFileContentApiMock = vi.mocked(getMemoryFileContentApi)
const getMemoryOverviewApiMock = vi.mocked(getMemoryOverviewApi)
const getMemoryVersionDiffApiMock = vi.mocked(getMemoryVersionDiffApi)
const getMemoryVersionsApiMock = vi.mocked(getMemoryVersionsApi)
const rollbackMemoryVersionApiMock = vi.mocked(rollbackMemoryVersionApi)

const overviewResponse = {
  summaryCount: 1,
  candidateStatusCounts: { PENDING: 1 },
  managedFileCount: 1,
  maintenanceRunning: false,
  maintenanceLastRun: {
    trigger: 'manual',
    processedCount: 1,
    promotedCount: 1,
    mergedCount: 0,
    correctedCount: 0,
    forgottenCount: 0,
    rejectedCount: 0,
    failedCount: 0,
    handledCandidateIds: ['candidate-1'],
    touchedFiles: true,
    message: 'done',
  },
  compressionRules: {
    summaryEnabled: true,
    summaryType: 'rolling_session_summary',
    maxRecentMessages: 10,
    questionsPerSummary: 5,
    maxTokens: 2000,
    autoCompactRecentMessages: 6,
    minimalContextRecentMessages: 4,
    microCompactThreshold: 0.5,
    autoCompactThreshold: 0.75,
    minimalContextThreshold: 0.9,
    approximateCharsPerToken: 4,
  },
  recentActivity: {
    eventCount: 1,
    summaryRefreshCount: 1,
    contextCompactionCount: 1,
    minimalContextActivationCount: 0,
    compactedMessageCount: 2,
    latestSummaryAt: '2026-06-21T10:00:00Z',
    latestSummaryTopic: '支付排障',
    latestSummaryVersion: 3,
    latestCompactionAt: '2026-06-21T10:01:00Z',
    latestCompactionLevel: 'AUTO',
    latestMinimalContextMode: false,
    latestUsedBudgetTokens: 100,
    latestMaxBudgetTokens: 200,
    latestUsageRatio: 0.5,
    latestRetainedMessageCount: 8,
    latestDroppedMessageCount: 2,
  },
  recentEvents: [],
  summaries: [],
  candidates: [],
  managedFiles: [
    {
      path: 'memory/ops.md',
      fileName: 'ops.md',
      topic: '支付排障经验',
      status: 'ACTIVE',
      lastOperation: 'MERGE',
      latestVersion: 3,
      versionCount: 3,
      mutationCount: 2,
      updateTime: '2026-06-21 10:00:00',
    },
  ],
  recentMutations: [
    {
      id: 'mutation-1',
      createdAt: '2026-06-21T10:00:00Z',
      mutationType: 'ROLLBACK',
      memoryPath: 'memory/ops.md',
      fromVersion: 3,
      toVersion: 2,
      operation: 'ROLLBACK',
      status: 'SUCCESS',
      summary: 'rollback',
    },
  ],
}

const versionsResponse = [
  {
    memoryPath: 'memory/ops.md',
    versionNumber: 3,
    trigger: 'maintenance',
    operation: 'MERGE',
    status: 'SUCCESS',
    createdAt: '2026-06-21T10:00:00Z',
    snapshotContent: 'v3 snapshot',
  },
  {
    memoryPath: 'memory/ops.md',
    versionNumber: 2,
    trigger: 'manual',
    operation: 'ROLLBACK',
    status: 'SUCCESS',
    createdAt: '2026-06-21T09:00:00Z',
    snapshotContent: 'v2 snapshot',
  },
]

describe('MemoryPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('confirm', vi.fn(() => true))
    vi.stubGlobal('prompt', vi.fn(() => 'manual rollback'))
    getMemoryOverviewApiMock.mockResolvedValue(overviewResponse as never)
    getMemoryVersionsApiMock.mockResolvedValue(versionsResponse as never)
    getMemoryVersionDiffApiMock.mockResolvedValue({
      memoryPath: 'memory/ops.md',
      changedLines: 3,
      commonPrefixLines: 1,
      commonSuffixLines: 1,
      summary: 'v2 -> v3',
      previewLines: ['- old line', '+ new line'],
      fromVersion: {
        versionNumber: 2,
        content: 'old content',
      },
      toVersion: {
        versionNumber: 3,
        content: 'new content',
      },
    } as never)
    getMemoryFileContentApiMock.mockResolvedValueOnce('current file content').mockResolvedValueOnce('rolled back content')
    rollbackMemoryVersionApiMock.mockResolvedValue({
      memoryPath: 'memory/ops.md',
      targetVersion: 2,
      restoredVersion: 4,
    } as never)
  })

  it('支持版本对比与回滚后刷新文件内容', async () => {
    render(<MemoryPage />)

    expect((await screen.findAllByText('memory/ops.md')).length).toBeGreaterThan(0)
    const fileRow = screen.getAllByText('memory/ops.md')[0]?.closest('tr')
    expect(fileRow).not.toBeNull()

    await userEvent.click(within(fileRow as HTMLElement).getByRole('button', { name: /查看文件/ }))
    expect(await screen.findByRole('heading', { name: 'ops.md' })).toBeInTheDocument()
    expect(await screen.findByText('current file content')).toBeInTheDocument()

    await userEvent.click(within(fileRow as HTMLElement).getByRole('button', { name: /版本历史/ }))
    expect(await screen.findByRole('heading', { name: '版本历史' })).toBeInTheDocument()
    await waitFor(() => {
      expect(getMemoryVersionsApiMock).toHaveBeenCalledWith('memory/ops.md')
    })

    const versionModal = screen.getByRole('heading', { name: '版本历史' }).closest('.modal-card')
    expect(versionModal).not.toBeNull()
    const compareButton = within(versionModal as HTMLElement).getAllByRole('button', { name: /对比当前/ })[1]
    await userEvent.click(compareButton)

    expect(await screen.findByRole('heading', { name: '版本 Diff' })).toBeInTheDocument()
    expect(screen.getAllByText(/v2 -> v3/).length).toBeGreaterThan(0)
    expect(screen.getByText(/old line/)).toBeInTheDocument()
    expect(screen.getByText(/new line/)).toBeInTheDocument()

    const rollbackButton = within(versionModal as HTMLElement).getAllByRole('button', { name: /回滚到此版本/ })[1]
    await userEvent.click(rollbackButton)

    expect(await screen.findByText('rolled back content')).toBeInTheDocument()
    expect(rollbackMemoryVersionApiMock).toHaveBeenCalledWith('memory/ops.md', 2, 'manual rollback')
    expect(getMemoryOverviewApiMock).toHaveBeenCalledTimes(2)
    expect(getMemoryVersionsApiMock).toHaveBeenCalledTimes(2)
    expect(getMemoryFileContentApiMock).toHaveBeenCalledTimes(2)
  })
})
