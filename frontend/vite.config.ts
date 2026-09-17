import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

const backend = process.env.BACKEND_URL ?? 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': backend,
      '/mock-aws': backend,
      // AWS Marketplace가 등록 토큰을 form POST로 보내는 경로. 백엔드가 302로 응답한다.
      '/marketplace/fulfillment': backend,
    },
  },
})
