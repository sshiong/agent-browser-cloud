export type PlatformName = 'web' | 'windows' | 'macos' | 'linux';

export interface FileSelectOptions {
  multiple?: boolean;
  directory?: boolean;
  filters?: Array<{ name: string; extensions: string[] }>;
}

export interface SaveFileOptions {
  defaultPath?: string;
  filters?: Array<{ name: string; extensions: string[] }>;
}

export interface AppNotification {
  title: string;
  body?: string;
}

export interface LocalRuntimeStatus {
  available: boolean;
  executablePath?: string;
  reason: string;
}

export interface DesktopUpdateStatus {
  available: boolean;
  currentVersion: string;
  version?: string;
  body?: string;
  date?: string;
}

export interface PlatformAdapter {
  readonly platform: PlatformName;
  readonly desktop: boolean;

  openExternal(url: string): Promise<void>;
  selectFile(options: FileSelectOptions): Promise<string[]>;
  saveFile(options: SaveFileOptions): Promise<string | null>;
  showNotification(notification: AppNotification): Promise<void>;

  getSecureValue(key: string): Promise<string | null>;
  setSecureValue(key: string, value: string): Promise<void>;
  removeSecureValue(key: string): Promise<void>;

  getAppVersion(): Promise<string>;
  getInitialOpenUrls(): Promise<string[]>;
  onOpenUrls(handler: (urls: string[]) => void): Promise<() => void>;
  checkLocalRuntime(): Promise<LocalRuntimeStatus>;
  checkForUpdates(): Promise<DesktopUpdateStatus>;
  installAvailableUpdate(): Promise<void>;
}
