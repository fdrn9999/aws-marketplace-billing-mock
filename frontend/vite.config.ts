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
      // AWS Marketplace posts the registration token here (form POST), the backend answers with a 302.
      '/marketplace/fulfillment': backend,
    },
  },
})
