import { describe, expect, it } from 'vitest';
import { PlatformSecureStateStore } from './PlatformSecureStateStore';
import type {
  DesktopUpdateStatus,
  LocalRuntimeStatus,
  PlatformAdapter,
} from '@/platform';

class CredentialPlatform implements PlatformAdapter {
  readonly platform = 'macos' as const;
  readonly desktop = true;
  readonly values = new Map<string, string>();

  async getSecureValue(key: string) {
    return this.values.get(key) ?? null;
  }

  async setSecureValue(key: string, value: string) {
    this.values.set(key, value);
  }

  async removeSecureValue(key: string) {
    this.values.delete(key);
  }

  async openExternal() {}
  async selectFile() {
    return [];
  }
  async saveFile() {
    return null;
  }
  async showNotification() {}
  async getAppVersion() {
    return '0.1.0';
  }
  async getInitialOpenUrls() {
    return [];
  }
  async onOpenUrls() {
    return () => undefined;
  }
  async checkLocalRuntime(): Promise<LocalRuntimeStatus> {
    return { available: false, reason: 'not packaged' };
  }
  async checkForUpdates(): Promise<DesktopUpdateStatus> {
    return { available: false, currentVersion: '0.1.0' };
  }
  async installAvailableUpdate() {}
}

describe('PlatformSecureStateStore', () => {
  it('stores opaque OIDC state and maintains a durable key index', async () => {
    const platform = new CredentialPlatform();
    const store = new PlatformSecureStateStore(platform, 'state');

    await store.set('state:with/unicode-租户', 'pkce-secret');

    expect(await store.get('state:with/unicode-租户')).toBe('pkce-secret');
    expect(await store.getAllKeys()).toEqual(['state:with/unicode-租户']);
    expect([...platform.values.keys()]).not.toContain(
      'state:with/unicode-租户'
    );
  });

  it('removes the credential and its index entry for callers', async () => {
    const platform = new CredentialPlatform();
    const store = new PlatformSecureStateStore(platform, 'user');
    await store.set('primary', 'refresh-token');

    expect(await store.remove('primary')).toBe('refresh-token');
    expect(await store.get('primary')).toBeNull();
    expect(await store.getAllKeys()).toEqual([]);
  });

  it('serializes concurrent index updates without losing OIDC keys', async () => {
    const platform = new CredentialPlatform();
    const store = new PlatformSecureStateStore(platform, 'state');

    await Promise.all([
      store.set('first', 'one'),
      store.set('second', 'two'),
      store.set('third', 'three'),
    ]);

    expect(await store.getAllKeys()).toEqual(['first', 'second', 'third']);
  });

  it('rejects a corrupted OS-vault index instead of silently losing state', async () => {
    const platform = new CredentialPlatform();
    platform.values.set('oidc.index.state', '{invalid');
    const store = new PlatformSecureStateStore(platform, 'state');

    await expect(store.getAllKeys()).rejects.toThrow(
      'secure-store index is corrupted'
    );
  });
});
