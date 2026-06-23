import { useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { loginApi } from '../api/index.ts'

export function LoginPage() {
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setLoading(true)
    setError('')
    try {
      const response = await loginApi(username, password)
      if (!response.success) {
        setError(response.message ?? '登录失败')
        return
      }
      localStorage.setItem('token', response.data.token)
      localStorage.setItem('user', JSON.stringify(response.data.user))
      navigate('/diagnosis', { replace: true })
    } catch (error) {
      setError(error instanceof Error ? error.message : '登录失败，请稍后重试')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="login-page">
      <form className="login-card" onSubmit={handleSubmit}>
        <h1>DiagAgent</h1>
        <p>微服务故障定位智能体平台</p>
        <label>
          <span>用户名</span>
          <input value={username} onChange={(event) => setUsername(event.target.value)} placeholder="请输入用户名" required />
        </label>
        <label>
          <span>密码</span>
          <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} placeholder="请输入密码" required />
        </label>
        <button type="submit" className="primary-button" disabled={loading}>
          {loading ? '登录中...' : '登录'}
        </button>
        {error ? <div className="page-banner error">{error}</div> : null}
      </form>
    </div>
  )
}
