declare module '@novnc/novnc' {
  export interface RfbDisconnectEvent extends Event {
    detail: { clean: boolean };
  }

  export interface RfbSecurityFailureEvent extends Event {
    detail: { status: number; reason?: string };
  }

  export interface RfbClipboardEvent extends Event {
    detail: { text: string };
  }

  export default class RFB extends EventTarget {
    constructor(
      target: HTMLElement,
      urlOrChannel: string | WebSocket | RTCDataChannel,
      options?: {
        shared?: boolean;
        credentials?: {
          username?: string;
          password?: string;
          target?: string;
        };
        wsProtocols?: string[];
      }
    );

    background: string;
    clipViewport: boolean;
    compressionLevel: number;
    dragViewport: boolean;
    focusOnClick: boolean;
    qualityLevel: number;
    resizeSession: boolean;
    scaleViewport: boolean;
    viewOnly: boolean;

    disconnect(): void;
    focus(options?: FocusOptions): void;
    blur(): void;
    sendCtrlAltDel(): void;
    clipboardPasteFrom(text: string): void;
  }
}
