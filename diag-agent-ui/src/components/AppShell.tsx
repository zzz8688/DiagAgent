import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useEffect, useMemo, useState } from 'react'

const titles: Record<string, string> = {
  '/diagnosis': '智能诊断',
  '/history': '收藏夹',
  '/knowledge': '知识库',
  '/memory': '长期记忆',
  '/skills': '技能库',
  '/tools': '工具页',
  '/redis': 'Redis',
  '/protocol': 'MCP 协议',
  '/a2a': 'A2A 协议',
  '/rag': 'RAG 评测',
  '/sandbox': '沙箱与审计',
  '/system': '系统状态',
}

export function AppShell() {
  const location = useLocation()
  const navigate = useNavigate()
  const [displayName, setDisplayName] = useState('用户')

  useEffect(() => {
    const userStr = localStorage.getItem('user')
    if (!userStr) {
      setDisplayName('用户')
      return
    }
    try {
      const user = JSON.parse(userStr) as { nickname?: string; username?: string }
      setDisplayName(user.nickname || user.username || '用户')
    } catch {
      setDisplayName('用户')
    }
  }, [location.pathname])

  const pageTitle = useMemo(() => titles[location.pathname] ?? 'DiagAgent', [location.pathname])

  const handleLogout = () => {
    if (!window.confirm('确定要退出登录吗？')) {
      return
    }
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    navigate('/login', { replace: true })
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="sidebar-logo">DiagAgent</div>
        <nav className="sidebar-nav">
          <NavLink to="/diagnosis" className="nav-link">智能诊断</NavLink>
          <NavLink to="/history" className="nav-link">收藏夹</NavLink>
          <NavLink to="/knowledge" className="nav-link">知识库</NavLink>
          <NavLink to="/memory" className="nav-link">长期记忆</NavLink>
          <NavLink to="/skills" className="nav-link">技能库</NavLink>
          <NavLink to="/tools" className="nav-link">工具页</NavLink>
          <NavLink to="/redis" className="nav-link">Redis</NavLink>
          <NavLink to="/protocol" className="nav-link">MCP 协议</NavLink>
          <NavLink to="/a2a" className="nav-link">A2A 协议</NavLink>
          <NavLink to="/rag" className="nav-link">RAG 评测</NavLink>
          <NavLink to="/sandbox" className="nav-link">沙箱与审计</NavLink>
          <NavLink to="/system" className="nav-link">系统状态</NavLink>
        </nav>
      </aside>
      <div className="app-main">
        <header className="app-header">
          <div>
            <h1>{pageTitle}</h1>
            <p>DiagAgent 智能体诊断工作台</p>
          </div>
          <button className="ghost-button" onClick={handleLogout}>
            {displayName} · 退出登录
          </button>
        </header>
        <main className="app-content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
