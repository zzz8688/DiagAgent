import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useDiagnosisStore = defineStore('diagnosis', () => {
  const currentSessionId = ref(null)
  const diagnosisHistory = ref([])
  const isLoading = ref(false)
  const currentResult = ref('')

  function setSessionId(sessionId) {
    currentSessionId.value = sessionId
  }

  function addToHistory(record) {
    diagnosisHistory.value.unshift(record)
  }

  function setLoading(loading) {
    isLoading.value = loading
  }

  function setResult(result) {
    currentResult.value = result
  }

  return {
    currentSessionId,
    diagnosisHistory,
    isLoading,
    currentResult,
    setSessionId,
    addToHistory,
    setLoading,
    setResult
  }
})
