import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { useAuth } from '@/auth/AuthProvider';
import type { ResolvedTheme, ThemeMode } from '@/types/userPreferences';
import { useUpdateUserPreferences, useUserPreferences } from './themeQueries';

interface ThemeContextValue {
  mode: ThemeMode;
  resolvedTheme: ResolvedTheme;
  loading: boolean;
  saving: boolean;
  error: unknown;
  setMode: (mode: ThemeMode) => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

function systemTheme(): ResolvedTheme {
  return window.matchMedia('(prefers-color-scheme: light)').matches
    ? 'light'
    : 'dark';
}

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const auth = useAuth();
  const [systemPreference, setSystemPreference] =
    useState<ResolvedTheme>(systemTheme);
  const preferences = useUserPreferences(
    auth.identity?.tenantId,
    auth.identity?.actorId,
    auth.authenticated
  );
  const update = useUpdateUserPreferences(
    auth.identity?.tenantId,
    auth.identity?.actorId
  );
  const mode = preferences.data?.themeMode ?? 'SYSTEM';
  const resolvedTheme: ResolvedTheme =
    mode === 'SYSTEM'
      ? systemPreference
      : (mode.toLowerCase() as ResolvedTheme);

  useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: light)');
    const onChange = () =>
      setSystemPreference(media.matches ? 'light' : 'dark');
    onChange();
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, []);

  useEffect(() => {
    document.documentElement.dataset.theme = resolvedTheme;
    document.documentElement.style.colorScheme = resolvedTheme;
  }, [resolvedTheme]);

  const setMode = useCallback(
    (nextMode: ThemeMode) => {
      if (auth.authenticated && nextMode !== mode && !update.isPending) {
        update.mutate(nextMode);
      }
    },
    [auth.authenticated, mode, update]
  );

  const value = useMemo(
    () => ({
      mode,
      resolvedTheme,
      loading: preferences.isLoading,
      saving: update.isPending,
      error: update.error ?? preferences.error,
      setMode,
    }),
    [
      mode,
      resolvedTheme,
      preferences.isLoading,
      preferences.error,
      update.isPending,
      update.error,
      setMode,
    ]
  );

  return (
    <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
  );
}

// eslint-disable-next-line react-refresh/only-export-components
export function useTheme() {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error('useTheme must be used inside ThemeProvider');
  }
  return context;
}
