import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

const shared = process.env.WEB_OUT_DIR

export default defineConfig({
  base: '/login/',
  plugins: [vue()],
  define: {
    // The backend has the same version, so the page tells a newer release from its X-App-Version.
    __APP_VERSION__: JSON.stringify(process.env.APP_VERSION ?? 'dev'),
  },
  build: {
    outDir: shared ? `${shared}/login` : 'dist',
    // Old chunks stay in the shared directory, as in S3, so a page opened before a rebuild keeps loading.
    emptyOutDir: !shared,
    // The CSP allows no data: URLs.
    assetsInlineLimit: 0,
  },
  test: {
    environment: 'happy-dom',
  },
})
