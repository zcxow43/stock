import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // Backend (Spring Boot) has no CORS config of its own; proxy /api in dev
      // so the browser sees same-origin requests. 8080 is the integration
      // backend; a worktree worker points API_TARGET at its own backend port.
      '/api': {
        target: process.env.API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
