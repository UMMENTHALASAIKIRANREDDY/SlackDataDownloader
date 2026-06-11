import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

// Single source of env: the project root (one dir up from frontend/).
const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, projectRoot, '')
  const devPort = Number(env.VITE_DEV_PORT) || 3000
  // Dev-server proxy target for /api (so the browser can call the backend without CORS in dev).
  const apiProxyTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8081'

  return {
    plugins: [react()],
    // Read VITE_* vars (e.g. VITE_API_BASE_URL) from the root .env.
    envDir: projectRoot,
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
