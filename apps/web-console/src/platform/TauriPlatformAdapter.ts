import { invoke } from '@tauri-apps/api/core';
import type {
  AppNotification,
  DesktopUpdateStatus,
  FileSelectOptions,
  LocalRuntimeStatus,
  PlatformAdapter,
  PlatformName,
  SaveFileOptions,
} from './types';

export async function createTauriPlatformAdapter(): Promise<PlatformAdapter> {
  const { platform } = await import('@tauri-apps/plugin-os');
  return new TauriPlatformAdapter(normalizePlatform(platform()));
}

class TauriPlatformAdapter implements PlatformAdapter {
  readonly desktop = true;

  constructor(readonly platform: PlatformName) {}

  async openExternal(url: string) {
    const parsed = new URL(url);
    const loopback =
      parsed.hostname === 'localhost' ||
      parsed.hostname === '127.0.0.1' ||
      parsed.hostname === '[::1]';
    if (
      parsed.protocol !== 'https:' &&
      !(loopback && parsed.protocol === 'http:')
    ) {
      throw new Error(
        'Desktop external navigation only permits HTTPS or local development URLs'
      );
    }
    const { openUrl } = await import('@tauri-apps/plugin-opener');
    await openUrl(parsed.href);
  }

  async selectFile(options: FileSelectOptions) {
    const { open } = await import('@tauri-apps/plugin-dialog');
    const selected = await open({
      multiple: options.multiple,
      directory: options.directory,
      filters: options.filters,
    });
    if (!selected) return [];
    return Array.isArray(selected) ? selected : [selected];
  }

  async saveFile(options: SaveFileOptions) {
    const { save } = await import('@tauri-apps/plugin-dialog');
    return save({
      defaultPath: options.defaultPath,
      filters: options.filters,
    });
  }

  async showNotification(notification: AppNotification) {
    const { isPermissionGranted, requestPermission, sendNotification } =
      await import('@tauri-apps/plugin-notification');
    const granted =
      (await isPermissionGranted()) ||
      (await requestPermission()) === 'granted';
    if (granted) sendNotification(notification);
  }

  getSecureValue(key: string) {
    return invoke<string | null>('secure_get', { key });
  }

  setSecureValue(key: string, value: string) {
    return invoke<void>('secure_set', { key, value });
  }

  removeSecureValue(key: string) {
    return invoke<void>('secure_remove', { key });
  }

  async getAppVersion() {
    const { getVersion } = await import('@tauri-apps/api/app');
    return getVersion();
  }

  async getInitialOpenUrls() {
    const { getCurrent } = await import('@tauri-apps/plugin-deep-link');
    return (await getCurrent()) ?? [];
  }

  async onOpenUrls(handler: (urls: string[]) => void) {
    const { onOpenUrl } = await import('@tauri-apps/plugin-deep-link');
    return onOpenUrl(handler);
  }

  checkLocalRuntime() {
    return invoke<LocalRuntimeStatus>('check_local_runtime');
  }

  async checkForUpdates(): Promise<DesktopUpdateStatus> {
    const [{ getVersion }, { check }] = await Promise.all([
      import('@tauri-apps/api/app'),
      import('@tauri-apps/plugin-updater'),
    ]);
    const currentVersion = await getVersion();
    const update = await check({ timeout: 15_000 });
    if (!update) return { available: false, currentVersion };
    return {
      available: true,
      currentVersion,
      version: update.version,
      body: update.body,
      date: update.date,
    };
  }

  async installAvailableUpdate() {
    const [{ check }, { relaunch }] = await Promise.all([
      import('@tauri-apps/plugin-updater'),
      import('@tauri-apps/plugin-process'),
    ]);
    const update = await check({ timeout: 15_000 });
    if (!update) return;
    await update.downloadAndInstall();
    await relaunch();
  }
}

function normalizePlatform(value: string): PlatformName {
  if (value === 'windows' || value === 'macos' || value === 'linux') {
    return value;
  }
  throw new Error(`Unsupported desktop platform: ${value}`);
}
