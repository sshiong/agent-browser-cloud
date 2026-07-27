import type { StateStore } from 'oidc-client-ts';
import type { PlatformAdapter } from '@/platform';

/** Stores OIDC code-verifier, user and refresh-token state in the native OS credential vault. */
export class PlatformSecureStateStore implements StateStore {
  private indexMutation: Promise<void> = Promise.resolve();

  constructor(
    private readonly platform: PlatformAdapter,
    private readonly prefix: string
  ) {}

  async set(key: string, value: string) {
    const storageKey = this.storageKey(key);
    await this.platform.setSecureValue(storageKey, value);
    await this.mutateIndex((keys) =>
      keys.includes(key) ? keys : [...keys, key]
    );
  }

  async get(key: string) {
    return this.platform.getSecureValue(this.storageKey(key));
  }

  async remove(key: string) {
    const value = await this.get(key);
    await this.platform.removeSecureValue(this.storageKey(key));
    await this.mutateIndex((keys) => keys.filter((item) => item !== key));
    return value;
  }

  async getAllKeys(): Promise<string[]> {
    await this.indexMutation;
    return this.readIndex();
  }

  private storageKey(key: string) {
    const bytes = new TextEncoder().encode(`${this.prefix}:${key}`);
    let binary = '';
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return `oidc.${btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '')}`;
  }

  private async readIndex() {
    const value = await this.platform.getSecureValue(this.indexKey());
    if (!value) return [];
    try {
      const parsed: unknown = JSON.parse(value);
      return Array.isArray(parsed)
        ? parsed.filter((item): item is string => typeof item === 'string')
        : [];
    } catch {
      throw new Error('Desktop OIDC secure-store index is corrupted');
    }
  }

  private writeIndex(keys: string[]) {
    return this.platform.setSecureValue(
      this.indexKey(),
      JSON.stringify([...new Set(keys)].sort())
    );
  }

  private indexKey() {
    return `oidc.index.${this.prefix}`;
  }

  private mutateIndex(transform: (keys: string[]) => string[]) {
    const mutation = this.indexMutation.then(async () => {
      await this.writeIndex(transform(await this.readIndex()));
    });
    this.indexMutation = mutation.catch(() => undefined);
    return mutation;
  }
}
