import type {
  AppNotification,
  DesktopUpdateStatus,
  FileSelectOptions,
  LocalRuntimeStatus,
  PlatformAdapter,
} from './types';

export class WebPlatformAdapter implements PlatformAdapter {
  readonly platform = 'web' as const;
  readonly desktop = false;

  async openExternal(url: string) {
    const parsed = requireHttpsUrl(url);
    window.open(parsed.href, '_blank', 'noopener,noreferrer');
  }

  async selectFile(options: FileSelectOptions) {
    return new Promise<string[]>((resolve) => {
      const input = document.createElement('input');
      input.type = 'file';
      input.multiple = Boolean(options.multiple);
      input.accept =
        options.filters
          ?.flatMap((filter) =>
            filter.extensions.map((extension) => `.${extension}`)
          )
          .join(',') ?? '';
      input.addEventListener(
        'change',
        () => resolve(Array.from(input.files ?? []).map((file) => file.name)),
        { once: true }
      );
      input.click();
    });
  }

  async saveFile() {
    return null;
  }

  async showNotification(notification: AppNotification) {
    if (!('Notification' in window)) return;
    const permission =
      Notification.permission === 'default'
        ? await Notification.requestPermission()
        : Notification.permission;
    if (permission === 'granted') {
      new Notification(notification.title, { body: notification.body });
    }
  }

  async getSecureValue(): Promise<string | null> {
    throw new Error('Secure credential storage is only available in Desktop');
  }

  async setSecureValue() {
    throw new Error('Secure credential storage is only available in Desktop');
  }

  async removeSecureValue() {
    throw new Error('Secure credential storage is only available in Desktop');
  }

  async getAppVersion() {
    return import.meta.env.VITE_APP_VERSION?.trim() || 'web';
  }

  async getInitialOpenUrls() {
    return [];
  }

  async onOpenUrls() {
    return () => undefined;
  }

  async checkLocalRuntime(): Promise<LocalRuntimeStatus> {
    return {
      available: false,
      reason: 'Web 管理端不访问本机 Runtime',
    };
  }

  async checkForUpdates(): Promise<DesktopUpdateStatus> {
    return {
      available: false,
      currentVersion: await this.getAppVersion(),
    };
  }

  async installAvailableUpdate() {
    throw new Error('Web deployment updates are managed by the server');
  }
}

function requireHttpsUrl(value: string) {
  const parsed = new URL(value);
  const loopback =
    parsed.hostname === 'localhost' ||
    parsed.hostname === '127.0.0.1' ||
    parsed.hostname === '[::1]';
  if (
    parsed.protocol !== 'https:' &&
    !(loopback && parsed.protocol === 'http:')
  ) {
    throw new Error(
      'External navigation only permits HTTPS or local development URLs'
    );
  }
  return parsed;
}
