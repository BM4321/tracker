import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Two pages: the floating orb and the main window. Tauri loads them from dist/.
export default defineConfig({
  plugins: [react()],
  clearScreen: false,
  // ../shared holds the default habit rules
  server: { port: 1420, strictPort: true, fs: { allow: ['..'] } },
  build: {
    target: 'safari15',
    rollupOptions: {
      input: { main: 'index.html', orb: 'orb.html' },
    },
  },
});
