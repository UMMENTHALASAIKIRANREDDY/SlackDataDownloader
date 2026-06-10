import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// Config is env-driven (see .env / .env.example). VITE_* vars are read at build/dev time.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const devPort = Number(env.VITE_DEV_PORT) || 3000
  // Dev-server proxy target for /api (so the browser can call the backend without CORS in dev).
  const apiProxyTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8081'

  return {
    plugins: [react()],
    server: {
      port: devPort,
      proxy: {
        '/api': {
          target: apiProxyTarget,
          changeOrigin: true,
        },
      },
    },
  }
})
