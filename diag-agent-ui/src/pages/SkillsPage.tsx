import { useEffect, useRef, useState } from 'react'
import {
  downloadSkillApi,
  getSkillContentApi,
  getSkillExecutionPlanApi,
  getSkillsApi,
  uploadSkillApi,
} from '../api/index.ts'
import type { SkillDocument, SkillExecutionPlan } from '../types'
import { markdownToHtml } from '../utils/markdownToHtml.ts'

export function SkillsPage() {
  const [documents, setDocuments] = useState<SkillDocument[]>([])
  const [currentPage, setCurrentPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState('')
  const [previewDoc, setPreviewDoc] = useState<SkillDocument | null>(null)
  const [previewHtml, setPreviewHtml] = useState('')
  const [executionDoc, setExecutionDoc] = useState<SkillDocument | null>(null)
  const [executionPlan, setExecutionPlan] = useState<SkillExecutionPlan | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  async function loadDocuments(page = currentPage, size = pageSize) {
    setLoading(true)
    try {
      const data = await getSkillsApi(page, size)
      setDocuments(data.records ?? [])
      setTotal(data.total ?? 0)
      setStatus('')
    } catch (error) {
      setStatus(error instanceof Error ? `加载技能库失败：${error.message}` : '加载技能库失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadDocuments()
  }, [currentPage, pageSize])

  const bundledCount = documents.filter((document) => document.source === 'BUNDLED').length
  const externalCount = documents.filter((document) => document.source !== 'BUNDLED').length

  async function handlePreview(document: SkillDocument) {
    setPreviewDoc(document)
    try {
      const content = await getSkillContentApi(document.skillId)
      setPreviewHtml(await markdownToHtml(content, { enableSyntaxFix: true }))
    } catch (error) {
      setStatus(error instanceof Error ? `读取技能正文失败：${error.message}` : '读取技能正文失败')
      setPreviewHtml('')
    }
  }

  async function handleUpload(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file) {
      return
    }
    try {
      setLoading(true)
      await uploadSkillApi(file)
      setStatus(`上传成功：${file.name}`)
      await loadDocuments()
    } catch (error) {
      setStatus(error instanceof Error ? `上传失败：${error.message}` : '上传失败')
    } finally {
      setLoading(false)
      if (fileInputRef.current) {
        fileInputRef.current.value = ''
      }
    }
  }

  async function handleExecutionPreview(document: SkillDocument) {
    try {
      setExecutionDoc(document)
      const plan = await getSkillExecutionPlanApi(document.skillId)
      setExecutionPlan(plan)
      setStatus('')
    } catch (error) {
      setStatus(error instanceof Error ? `读取执行计划失败：${error.message}` : '读取执行计划失败')
      setExecutionPlan(null)
    }
  }

  return (
    <div className="page-stack">
      <section className="panel">
        <div className="panel-header split">
          <div>
            <h2>技能库</h2>
            <p>统一查看外置与内置 skills。skills 负责诊断流程、处理策略和轻量工作流，不负责保存知识库正文。</p>
          </div>
          <div className="toolbar-row">
            <button className="secondary-button" onClick={() => fileInputRef.current?.click()}>
              上传
            </button>
            <button className="secondary-button" onClick={() => void loadDocuments()}>
              刷新
            </button>
          </div>
        </div>
        {status ? <div className="page-banner info">{status}</div> : null}
        <div className="summary-grid">
          <article className="summary-card">
            <span>内置技能</span>
            <strong>{bundledCount}</strong>
          </article>
          <article className="summary-card">
            <span>外置技能</span>
            <strong>{externalCount}</strong>
          </article>
          <article className="summary-card">
            <span>当前页条目</span>
            <strong>{documents.length}</strong>
          </article>
        </div>
        <div className="page-banner info">
          如果你感觉“内置技能不止一个”，可以直接看 `skillId` 列确认当前接口实际返回了哪些技能。
        </div>
        <div className="table-wrapper">
          <table className="data-table">
            <thead>
              <tr>
                <th>skillId</th>
                <th>标题</th>
                <th>类型</th>
                <th>来源</th>
                <th>大小</th>
                <th>更新时间</th>
                <th>说明</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {documents.map((document) => (
                <tr key={document.skillId}>
                  <td>{document.skillId}</td>
                  <td>{document.title}</td>
                  <td>{document.type}</td>
                  <td>{document.source === 'BUNDLED' ? '内置' : '外置'}</td>
                  <td>{document.size}</td>
                  <td>{document.updateTime}</td>
                  <td>{document.description || '-'}</td>
                  <td className="actions-cell">
                    <button className="link-button" onClick={() => void handlePreview(document)}>
                      查看
                    </button>
                    <button className="link-button" onClick={() => void handleExecutionPreview(document)}>
                      执行计划
                    </button>
                    <button className="link-button" onClick={() => downloadSkillApi(document.skillId)}>
                      下载
                    </button>
                  </td>
                </tr>
              ))}
              {!loading && documents.length === 0 ? (
                <tr>
                  <td colSpan={8}>
                    <div className="empty-state">暂无技能包，请先上传用于诊断编排的 SKILL.md 或 ZIP 技能包。</div>
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
        <div className="pagination">
          <label>
            每页
            <select
              value={pageSize}
              onChange={(event) => {
                setPageSize(Number(event.target.value))
                setCurrentPage(1)
              }}
            >
              {[5, 10, 20, 50].map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
            条
          </label>
          <span>共 {total} 条</span>
          <button className="secondary-button" onClick={() => setCurrentPage((page) => Math.max(1, page - 1))} disabled={currentPage === 1}>
            上一页
          </button>
          <span>{currentPage}</span>
          <button className="secondary-button" onClick={() => setCurrentPage((page) => page + 1)} disabled={currentPage * pageSize >= total}>
            下一页
          </button>
        </div>
        <input
          ref={fileInputRef}
          type="file"
          hidden
          accept=".zip,.md"
          onChange={(event) => void handleUpload(event)}
        />
      </section>

      {previewDoc ? (
        <div className="modal-overlay" onClick={() => setPreviewDoc(null)}>
          <div className="modal-card large" onClick={(event) => event.stopPropagation()}>
            <div className="panel-header split">
              <div>
                <h2>{previewDoc.title}</h2>
                {previewDoc.description ? <p>{previewDoc.description}</p> : null}
              </div>
              <button className="ghost-button" onClick={() => setPreviewDoc(null)}>
                关闭
              </button>
            </div>
            <div className="markdown-body" dangerouslySetInnerHTML={{ __html: previewHtml }} />
          </div>
        </div>
      ) : null}

      {executionDoc ? (
        <div className="modal-overlay" onClick={() => setExecutionDoc(null)}>
          <div className="modal-card large" onClick={(event) => event.stopPropagation()}>
            <div className="panel-header split">
              <div>
                <h2>{executionDoc.title} · 执行计划</h2>
                <p>对应 `/api/skills/execution/{'{'}skillId{'}'}` 的当前返回结果。</p>
              </div>
              <button className="ghost-button" onClick={() => setExecutionDoc(null)}>
                关闭
              </button>
            </div>
            <pre className="json-block">{JSON.stringify(executionPlan, null, 2)}</pre>
          </div>
        </div>
      ) : null}
    </div>
  )
}
