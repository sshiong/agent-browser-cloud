import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import path from 'path';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const proxyTarget = env.VITE_DEV_PROXY_TARGET || 'http://127.0.0.1:8080';
  const desktopProxyTarget =
    env.VITE_DESKTOP_PROXY_TARGET || 'http://127.0.0.1:6080';
  const proxy = {
    '/api': {
      target: proxyTarget,
      changeOrigin: true,
    },
    '/desktop': {
      target: desktopProxyTarget,
      changeOrigin: true,
      ws: true,
    },
  };

  return {
    plugins: [react(), tailwindcss()],
    build: {
      // noVNC 1.7 performs an asynchronous WebCodecs capability probe at module load.
      // Keep the modern module syntax instead of transpiling it into an invalid legacy bundle.
      target: 'esnext',
    },
    optimizeDeps: {
      esbuildOptions: {
        target: 'esnext',
      },
    },
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    server: {
      port: 3000,
      proxy,
    },
    preview: {
      proxy,
    },
  };
});
