import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { McpPage } from '../pages/McpPage'
import { getExportableMcpToolsApi, getMcpRegistryApi } from '../api/index.ts'

vi.mock('../api/index.ts', () => ({
  getExportableMcpToolsApi: vi.fn(),
  getMcpRegistryApi: vi.fn(),
}))

const getExportableMcpToolsApiMock = vi.mocked(getExportableMcpToolsApi)
const getMcpRegistryApiMock = vi.mocked(getMcpRegistryApi)

describe('McpPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getMcpRegistryApiMock.mockResolvedValue({
      servers: [
        { serverId: 'diagagent-mcp-server', displayName: 'DiagAgent MCP Server', transport: 'http' },
        { serverId: 'server-1', displayName: 'Server One', transport: 'sse' },
        { serverId: 'server-2', displayName: 'Server Two', transport: 'streamable-http' },
      ],
      tools: [
        { name: 'tool-a', serverId: 'server-1' },
        { name: 'tool-a-2', serverId: 'server-1' },
        { name: 'tool-b', serverId: 'server-2' },
        { name: 'self-tool', serverId: 'diagagent-mcp-server' },
      ],
      resources: [{ uri: 'resource://a', serverId: 'server-1' }],
      prompts: [{ name: 'prompt-a', serverId: 'server-1' }],
      capabilitySnapshots: [
        {
          serverId: 'server-1',
          toolCount: 1,
          resourceCount: 1,
          promptCount: 1,
          toolRegistrations: [{ toolName: 'tool-a', description: 'Tool A Description' }],
          resourceRegistrations: [{ uri: 'resource://a', name: 'Resource A', mimeType: 'text/plain' }],
          promptRegistrations: [{ promptName: 'prompt-a', description: 'Prompt A Description' }],
        },
        {
          serverId: 'server-2',
          toolCount: 1,
          resourceCount: 0,
          promptCount: 0,
          toolRegistrations: [{ toolName: 'tool-b', description: 'Tool B Description' }],
          resourceRegistrations: [],
          promptRegistrations: [],
        },
      ],
    } as never)
    getExportableMcpToolsApiMock.mockResolvedValue([
      { name: 'queryLogs', description: '查询日志' },
      { name: 'runSkill', description: '执行技能' },
    ] as never)
  })

  it('协议页区分外部 MCP 服务和自导出 MCP 内容', async () => {
    render(<McpPage />)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Server One/ })).toBeInTheDocument()
    })

    const registeredToolsCard = screen.getByText('已注册外部工具').closest('.summary-card')
    expect(registeredToolsCard).not.toBeNull()
    expect(within(registeredToolsCard as HTMLElement).getByText('3')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /DiagAgent MCP Server/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Server One/ })).toBeInTheDocument()
    expect(screen.getByText('服务名')).toBeInTheDocument()
    expect(screen.getAllByText('Server One').length).toBeGreaterThan(0)
    expect(screen.getByText('工具数')).toBeInTheDocument()
    expect(screen.queryByText(/http:\/\/example.com/)).not.toBeInTheDocument()
    expect(screen.queryByText('none')).not.toBeInTheDocument()
    expect(screen.getByText('queryLogs')).toBeInTheDocument()
    expect(screen.getByText('runSkill')).toBeInTheDocument()
    expect(screen.getByText('tool-a-2')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Server Two/ }))
    expect(await screen.findByText(/tool-b/)).toBeInTheDocument()
    expect(screen.queryByText(/tool-a-2/)).not.toBeInTheDocument()
  })
})
