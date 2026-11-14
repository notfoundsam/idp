import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

const shared = process.env.WEB_OUT_DIR

export const VENDOR = 'vendor'

export default defineConfig({
  base: '/console/',
  plugins: [vue()],
  define: {
    __APP_VERSION__: JSON.stringify(process.env.APP_VERSION ?? 'dev'),
  },
  build: {
    outDir: shared ? `${shared}/console` : 'dist',
    emptyOutDir: !shared,
    assetsInlineLimit: 0,
    rolldownOptions: {
      output: {
        // The libraries change less often than the app, so a release that changes only the app keeps them cached in browsers.
        codeSplitting: { groups: [{ name: VENDOR, test: /[\\/]node_modules[\\/]/ }] },
      },
    },
  },
  test: {
    environment: 'happy-dom',
  },
})
