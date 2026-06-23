import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/AppShell'
import { ProtectedRoute } from './components/ProtectedRoute'
import { DiagnosisPage } from './pages/DiagnosisPage'
import { HistoryPage } from './pages/HistoryPage'
import { KnowledgePage } from './pages/KnowledgePage'
import { LoginPage } from './pages/LoginPage'
import { MemoryPage } from './pages/MemoryPage'
import { A2aPage } from './pages/A2aPage'
import { McpPage } from './pages/McpPage'
import { RedisPage } from './pages/RedisPage'
import { RagPage } from './pages/RagPage'
import { SandboxPage } from './pages/SandboxPage'
import { SkillsPage } from './pages/SkillsPage'
import { SystemPage } from './pages/SystemPage'
import { ToolsPage } from './pages/ToolsPage'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          element={
            <ProtectedRoute>
              <AppShell />
            </ProtectedRoute>
          }
        >
          <Route path="/diagnosis" element={<DiagnosisPage />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/knowledge" element={<KnowledgePage />} />
          <Route path="/memory" element={<MemoryPage />} />
          <Route path="/skills" element={<SkillsPage />} />
          <Route path="/tools" element={<ToolsPage />} />
          <Route path="/redis" element={<RedisPage />} />
          <Route path="/protocol" element={<McpPage />} />
          <Route path="/a2a" element={<A2aPage />} />
          <Route path="/rag" element={<RagPage />} />
          <Route path="/sandbox" element={<SandboxPage />} />
          <Route path="/system" element={<SystemPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/diagnosis" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
