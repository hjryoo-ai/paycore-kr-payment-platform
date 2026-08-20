// vitest 설정을 같은 파일에 두려면 vitest/config 의 defineConfig 를 써야 한다.
// vite 의 것은 test 키를 모른다.
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// 개발 서버는 두 백엔드로 프록시한다. 운영에서는 nginx 가 같은 경로를 같은 곳으로 넘긴다
// (ops-dashboard/nginx.conf) — 개발과 운영에서 프런트 코드가 보는 경로가 같아야
// "로컬에서는 되는데 배포하면 안 되는" 부류의 문제가 생기지 않는다.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api/v1/payments': { target: 'http://localhost:8081', changeOrigin: true },
      '/api/v1/ops': { target: 'http://localhost:8081', changeOrigin: true },
      '/api/v1/recon': { target: 'http://localhost:8085', changeOrigin: true },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
  },
})
