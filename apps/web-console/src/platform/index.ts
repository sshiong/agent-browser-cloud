import { isTauri } from '@tauri-apps/api/core';
import { WebPlatformAdapter } from './WebPlatformAdapter';
import type { PlatformAdapter } from './types';

export async function createPlatformAdapter(): Promise<PlatformAdapter> {
  if (!isTauri()) return new WebPlatformAdapter();
  const { createTauriPlatformAdapter } = await import('./TauriPlatformAdapter');
  return createTauriPlatformAdapter();
}

export type {
  AppNotification,
  DesktopUpdateStatus,
  FileSelectOptions,
  LocalRuntimeStatus,
  PlatformAdapter,
  PlatformName,
  SaveFileOptions,
} from './types';
