import { useEffect, useState } from 'react'
import { deleteAllHistoryApi, deleteHistoryApi, getHistoryPageApi } from '../api/index.ts'
import type { DiagnosisRecord } from '../types'

function formatTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString('zh-CN', { hour12: false })
}

function truncate(text: string, maxLength = 120) {
  return text.length > maxLength ? `${text.slice(0, maxLength)}...` : text
}

export function HistoryPage() {
  const [records, setRecords] = useState<DiagnosisRecord[]>([])
  const [currentPage, setCurrentPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState('')
  const [selectedRecord, setSelectedRecord] = useState<DiagnosisRecord | null>(null)

  async function loadHistory(page = currentPage, size = pageSize) {
    setLoading(true)
    try {
      const data = await getHistoryPageApi(page, size)
      setRecords(data.records ?? [])
      setTotal(data.total ?? 0)
      setStatus('')
    } catch (error) {
      setStatus(error instanceof Error ? `加载收藏夹失败：${error.message}` : '加载收藏夹失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadHistory()
  }, [currentPage, pageSize])

  async function handleDelete(id: number) {
    if (!window.confirm('确定取消收藏这条记录吗？')) {
      return
    }
    try {
      await deleteHistoryApi(id)
      await loadHistory()
      setStatus('已取消收藏')
    } catch (error) {
      setStatus(error instanceof Error ? `取消收藏失败：${error.message}` : '取消收藏失败')
    }
  }

  async function handleDeleteAll() {
    if (!window.confirm('确定全部取消收藏吗？')) {
      return
    }
    try {
      await deleteAllHistoryApi()
      await loadHistory(1, pageSize)
      setCurrentPage(1)
      setStatus('已清空收藏夹')
    } catch (error) {
      setStatus(error instanceof Error ? `清空收藏夹失败：${error.message}` : '清空收藏夹失败')
    }
  }

  return (
    <div className="page-stack">
      <section className="panel">
        <div className="panel-header split">
          <div>
            <h2>收藏夹</h2>
            <p>保存可复用的诊断结果</p>
          </div>
          <div className="toolbar-row">
            <button className="secondary-button" onClick={() => void loadHistory()}>
              刷新
            </button>
            <button className="danger-button" onClick={() => void handleDeleteAll()} disabled={records.length === 0}>
              全部取消收藏
            </button>
          </div>
        </div>
        {status ? <div className="page-banner info">{status}</div> : null}
        <div className="table-wrapper">
          <table className="data-table">
            <thead>
              <tr>
                <th>查询内容</th>
                <th>诊断结论</th>
                <th>时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {records.map((record) => (
                <tr key={record.id}>
                  <td>{record.query}</td>
                  <td>{truncate(record.conclusion)}</td>
                  <td>{formatTime(record.createdAt)}</td>
                  <td className="actions-cell">
                    <button className="link-button" onClick={() => setSelectedRecord(record)}>
                      查看
                    </button>
                    <button className="link-button danger" onClick={() => void handleDelete(record.id)}>
                      取消收藏
                    </button>
                  </td>
                </tr>
              ))}
              {!loading && records.length === 0 ? (
                <tr>
                  <td colSpan={4}>
                    <div className="empty-state">暂无收藏的诊断记录。</div>
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
      </section>

      {selectedRecord ? (
        <div className="modal-overlay" onClick={() => setSelectedRecord(null)}>
          <div className="modal-card" onClick={(event) => event.stopPropagation()}>
            <div className="panel-header split">
              <h2>诊断详情</h2>
              <button className="ghost-button" onClick={() => setSelectedRecord(null)}>
                关闭
              </button>
            </div>
            <div className="detail-grid">
              <div>
                <strong>查询内容</strong>
                <p>{selectedRecord.query}</p>
              </div>
              <div>
                <strong>诊断时间</strong>
                <p>{formatTime(selectedRecord.createdAt)}</p>
              </div>
              <div className="detail-full">
                <strong>诊断结论</strong>
                <pre>{selectedRecord.conclusion}</pre>
              </div>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}
