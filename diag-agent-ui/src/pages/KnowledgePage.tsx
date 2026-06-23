import { useEffect, useRef, useState } from 'react'
import {
  rebuildKnowledgeApi,
  downloadKnowledgeApi,
  getKnowledgeApi,
  getKnowledgeContentApi,
  reloadKnowledgeApi,
  uploadKnowledgeApi,
} from '../api/index.ts'
import { markdownToHtml } from '../utils/markdownToHtml.ts'
import type { KnowledgeDocument } from '../types'

export function KnowledgePage() {
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([])
  const [currentPage, setCurrentPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState('')
  const [previewDoc, setPreviewDoc] = useState<KnowledgeDocument | null>(null)
  const [previewHtml, setPreviewHtml] = useState('')
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  async function loadDocuments(page = currentPage, size = pageSize) {
    setLoading(true)
    try {
      const data = await getKnowledgeApi(page, size)
      setDocuments(data.records ?? [])
      setTotal(data.total ?? 0)
      setStatus('')
    } catch (error) {
      setStatus(error instanceof Error ? `加载知识库失败：${error.message}` : '加载知识库失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadDocuments()
  }, [currentPage, pageSize])

  async function handlePreview(document: KnowledgeDocument) {
    setPreviewDoc(document)
    if (document.type !== 'MD') {
      setPreviewHtml('')
      return
    }
    try {
      setPreviewHtml('')
      const content = await getKnowledgeContentApi(document.fileName)
      setPreviewHtml(await markdownToHtml(content, { enableSyntaxFix: true }))
    } catch (error) {
      setPreviewDoc(null)
      setPreviewHtml('')
      setStatus(error instanceof Error ? `读取知识内容失败：${error.message}` : '读取知识内容失败')
    }
  }

  async function handleUpload(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file) {
      return
    }
    try {
      setLoading(true)
      await uploadKnowledgeApi(file)
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

  async function handleReload() {
    try {
      setLoading(true)
      const message = await reloadKnowledgeApi()
      setStatus(message || '知识库增量重扫成功')
      await loadDocuments()
    } catch (error) {
      setStatus(error instanceof Error ? `知识库增量重扫失败：${error.message}` : '知识库增量重扫失败')
    } finally {
      setLoading(false)
    }
  }

  async function handleRebuild() {
    try {
      setLoading(true)
      const message = await rebuildKnowledgeApi()
      setStatus(message || '知识库全量重建成功')
      await loadDocuments()
    } catch (error) {
      setStatus(error instanceof Error ? `知识库全量重建失败：${error.message}` : '知识库全量重建失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="page-stack">
      <section className="panel">
        <div className="panel-header split">
          <div>
            <h2>知识库</h2>
            <p>这里存放案例、规则、手册等知识资料，供诊断时检索引用，不负责保存技能流程。</p>
          </div>
          <div className="toolbar-row">
            <button className="secondary-button" onClick={() => fileInputRef.current?.click()}>
              上传
            </button>
            <button className="secondary-button" onClick={() => void handleReload()} disabled={loading}>
              增量重扫
            </button>
            <button className="secondary-button" onClick={() => void handleRebuild()} disabled={loading}>
              全量重建索引
            </button>
            <button className="secondary-button" onClick={() => void loadDocuments()}>
              刷新
            </button>
          </div>
        </div>
        {status ? <div className="page-banner info">{status}</div> : null}
        <div className="page-banner info">
          默认会自动做增量加载。`增量重扫` 只重新扫描新增、修改、删除的知识文件；`全量重建索引` 会清掉已登记的知识向量并重新入库，适合在 metadata 规则变更后彻底刷新旧索引。
        </div>
        <div className="table-wrapper">
          <table className="data-table">
            <thead>
              <tr>
                <th>标题</th>
                <th>类型</th>
                <th>大小</th>
                <th>更新时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {documents.map((document) => (
                <tr key={document.fileName}>
                  <td>{document.title}</td>
                  <td>{document.type}</td>
                  <td>{document.size}</td>
                  <td>{document.updateTime}</td>
                  <td className="actions-cell">
                    <button className="link-button" onClick={() => void handlePreview(document)}>
                      查看
                    </button>
                    <button className="link-button" onClick={() => downloadKnowledgeApi(document.fileName)}>
                      下载
                    </button>
                  </td>
                </tr>
              ))}
              {!loading && documents.length === 0 ? (
                <tr>
                  <td colSpan={5}>
                    <div className="empty-state">暂无知识资料，请先上传案例、规则或手册文档。</div>
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
          accept=".md,.pdf"
          onChange={(event) => void handleUpload(event)}
        />
      </section>

      {previewDoc ? (
        <div className="modal-overlay" onClick={() => setPreviewDoc(null)}>
          <div className="modal-card large" onClick={(event) => event.stopPropagation()}>
            <div className="panel-header split">
              <h2>{previewDoc.title}</h2>
              <button className="ghost-button" onClick={() => setPreviewDoc(null)}>
                关闭
              </button>
            </div>
            {previewDoc.type === 'MD' ? (
              <div className="markdown-body" dangerouslySetInnerHTML={{ __html: previewHtml }} />
            ) : (
              <div className="empty-state">PDF 文档请点击下载按钮查看。</div>
            )}
          </div>
        </div>
      ) : null}
    </div>
  )
}
