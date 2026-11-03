import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

const shared = process.env.WEB_OUT_DIR

export default defineConfig({
  base: '/login/',
  plugins: [vue()],
  build: {
    outDir: shared ? `${shared}/login` : 'dist',
    // Old chunks stay in the shared directory, as in S3, so a page opened before a rebuild keeps loading.
    emptyOutDir: !shared,
    // The CSP allows no data: URLs.
    assetsInlineLimit: 0,
  },
})
