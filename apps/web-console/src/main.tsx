import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './app/App';
import { AuthProvider } from './auth/AuthProvider';
import { createPlatformAdapter } from './platform';
import { PlatformProvider } from './platform/PlatformProvider';
import './index.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
});

async function bootstrap() {
  const platform = await createPlatformAdapter();
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <PlatformProvider adapter={platform}>
        <QueryClientProvider client={queryClient}>
          <AuthProvider>
            <BrowserRouter>
              <App />
            </BrowserRouter>
          </AuthProvider>
        </QueryClientProvider>
      </PlatformProvider>
    </StrictMode>
  );
}

void bootstrap();
