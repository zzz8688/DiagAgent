import axios from 'axios'

const API_BASE_URL = 'http://localhost:8080/api'

const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 300000,
  headers: {
    'Content-Type': 'application/json'
  }
})

function fixGarbledText(text) {
  return text
}

export const diagnoseApi = (query, sessionId, onProgress) => {
  return new Promise((resolve, reject) => {
    let result = ''

    const requestBody = { query }
    if (sessionId) {
      requestBody.sessionId = sessionId
    }

    fetch(`${API_BASE_URL}/agent/diagnose`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(requestBody)
    }).then(response => {
      if (!response.ok) {
        reject(new Error(`HTTP error! status: ${response.status}`))
        return
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')

      function read() {
        reader.read().then(({ done, value }) => {
          if (done) {
            if (onProgress) {
              onProgress('[DONE]')
            }
            resolve(result)
            return
          }

          const chunk = decoder.decode(value, { stream: true })

          const lines = chunk.split('\n')
          lines.forEach(line => {
            if (line.startsWith('data:')) {
              let data = line.substring(5).trim()
              if (data && data !== '[DONE]') {
                const fixedData = fixGarbledText(data)
                result += fixedData
                if (onProgress) {
                  onProgress(fixedData)
                }
              }
            }
          })

          read()
        }).catch(error => {
          reject(error)
        })
      }

      read()
    }).catch(error => {
      reject(error)
    })
  })
}

export const getHistoryApi = () => {
  return api.get('/agent/history')
}

export const getHistoryPageApi = (pageNum, pageSize) => {
  return api.get('/agent/history/page', {
    params: { pageNum, pageSize }
  })
}

export const deleteHistoryApi = (id) => {
  return api.delete(`/agent/history/${id}`)
}

export const deleteAllHistoryApi = () => {
  return api.delete('/agent/history')
}

export const getSessionsApi = () => {
  return api.get('/agent/session')
}

export const createSessionApi = (session) => {
  return api.post('/agent/session', session)
}

export const updateSessionApi = (sessionId, session) => {
  return api.put(`/agent/session/${sessionId}`, session)
}

export const deleteSessionApi = (sessionId) => {
  return api.delete(`/agent/session/${sessionId}`)
}

export const getKnowledgeApi = (page = 1, size = 10) => {
  return api.get('/knowledge/list', {
    params: { page, size }
  })
}

export const getKnowledgeContentApi = (fileName) => {
  return api.get(`/knowledge/content/${fileName}`)
}

export const downloadKnowledgeApi = (fileName) => {
  const url = `${API_BASE_URL}/knowledge/download/${encodeURIComponent(fileName)}`
  const link = document.createElement('a')
  link.href = url
  link.setAttribute('download', fileName)
  link.setAttribute('target', '_blank')
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}

export const uploadKnowledgeApi = (file) => {
  const formData = new FormData()
  formData.append('file', file)

  return axios.post(`${API_BASE_URL}/knowledge/upload`, formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  })
}

export const getSystemTopologyApi = () => {
  return api.get('/system/topology')
}

export const getSystemHealthApi = () => {
  return api.get('/system/health')
}

export default api
