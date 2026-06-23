import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

const backendTarget = process.env.VITE_DEV_PROXY_TARGET || 'http://127.0.0.1:8081'

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        target: backendTarget,
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setupTests.ts',
    css: true,
  },
})
