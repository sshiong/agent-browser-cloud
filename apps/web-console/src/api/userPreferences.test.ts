import { afterEach, describe, expect, it, vi } from 'vitest';
import { getUserPreferences, updateUserPreferences } from './userPreferences';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('user preference API', () => {
  it('reads the authenticated actor preference', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          themeMode: 'SYSTEM',
          source: 'SYSTEM_DEFAULT',
          updatedAt: null,
          version: 0,
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(getUserPreferences()).resolves.toMatchObject({
      themeMode: 'SYSTEM',
      version: 0,
    });
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/user-preferences',
      expect.objectContaining({ headers: expect.any(Object) })
    );
  });

  it('persists an explicit theme mode', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          themeMode: 'LIGHT',
          source: 'USER_OVERRIDE',
          updatedAt: '2026-07-31T03:00:00Z',
          version: 1,
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      )
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      updateUserPreferences({ themeMode: 'LIGHT' })
    ).resolves.toMatchObject({ themeMode: 'LIGHT', version: 1 });
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/user-preferences',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ themeMode: 'LIGHT' }),
      })
    );
  });
});
