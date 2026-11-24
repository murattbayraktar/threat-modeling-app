import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Geliştirmede /api istekleri Spring Boot backend'ine (8080) yönlendirilir.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' },
  },
  optimizeDeps: {
    exclude: ['@mlc-ai/web-llm'],
  },
  build: {
    chunkSizeWarningLimit: 7000,
  },
});
