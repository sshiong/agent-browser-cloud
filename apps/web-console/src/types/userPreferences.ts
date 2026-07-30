export type ThemeMode = 'SYSTEM' | 'DARK' | 'LIGHT';
export type ResolvedTheme = 'dark' | 'light';

export interface UserPreferencesView {
  themeMode: ThemeMode;
  source: 'SYSTEM_DEFAULT' | 'USER_OVERRIDE';
  updatedAt: string | null;
  version: number;
}

export interface UpdateUserPreferencesRequest {
  themeMode: ThemeMode;
}
