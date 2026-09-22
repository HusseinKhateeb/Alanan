import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig({
  plugins: [react(), VitePWA({
    registerType: 'prompt',
    includeAssets: ['icon.svg', 'apple-touch-icon.png'],
    manifest: {
      name: 'Al Anan Roastery | محمص العنان',
      short_name: 'Alanan',
      description: 'Accounting and business management for Al Anan Roastery',
      start_url: '/',
      scope: '/',
      display: 'standalone',
      theme_color: '#213d32',
      background_color: '#f5f4ef',
      icons: [
        { src: '/icon-192.png', sizes: '192x192', type: 'image/png' },
        { src: '/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any maskable' },
      ],
    },
    workbox: {
      globPatterns: ['**/*.{js,css,html,png,svg,woff2}'],
      navigateFallbackDenylist: [/^\/api(?:\/|$)/, /^\/actuator(?:\/|$)/],
      // Cache only the shell. Authenticated API data needs an explicit owner-aware policy.
      runtimeCaching: [],
    },
  })],
  server: { proxy: {
    '/api': 'http://localhost:8080',
    '/actuator': 'http://localhost:8080',
  } },
  test: { environment: 'jsdom', setupFiles: ['./src/test-setup.ts'], css: false },
})
