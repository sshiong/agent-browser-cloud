import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Routes, Route, Link } from 'react-router-dom';
import { SessionPage } from '../pages/SessionPage';
import { SessionsPage } from '../pages/SessionsPage';

const queryClient = new QueryClient();

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <div
          style={{
            fontFamily: 'system-ui, sans-serif',
            maxWidth: 1200,
            margin: '0 auto',
            padding: '0 24px',
          }}
        >
          <header
            style={{
              borderBottom: '1px solid #eee',
              padding: '16px 0',
              marginBottom: 24,
            }}
          >
            <nav style={{ display: 'flex', gap: 24, alignItems: 'center' }}>
              <Link
                to="/"
                style={{
                  fontSize: 20,
                  fontWeight: 'bold',
                  textDecoration: 'none',
                  color: '#333',
                }}
              >
                Agent Browser Cloud
              </Link>
              <Link to="/" style={{ textDecoration: 'none', color: '#666' }}>
                Sessions
              </Link>
            </nav>
          </header>
          <main>
            <Routes>
              <Route path="/" element={<SessionsPage />} />
              <Route path="/sessions/:sessionId" element={<SessionPage />} />
            </Routes>
          </main>
        </div>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
