import { LoaderCircle, MonitorOff, Radio } from 'lucide-react';
import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';
import {
  createRemoteDesktopConnection,
  isSessionApiError,
} from '@/api/session';
import type {
  RfbClipboardEvent,
  RfbDisconnectEvent,
  RfbSecurityFailureEvent,
} from '@novnc/novnc';
import { desktopQualityLevel, type DesktopQuality } from './desktopQuality';

export type DesktopConnectionState =
  'CONNECTING' | 'CONNECTED' | 'DISCONNECTED' | 'FAILED';

export interface NoVncViewportHandle {
  writeUserClipboard(value: string): boolean;
}

export interface UserClipboardObservation {
  value: string;
  observedAt: string;
}

export const NoVncViewport = forwardRef<
  NoVncViewportHandle,
  {
    sessionId: string;
    bindingEpoch: number;
    viewOnly?: boolean;
    quality?: DesktopQuality;
    onConnectionState?: (state: DesktopConnectionState) => void;
    onUnexpectedDisconnect?: () => void;
    onConnectionId?: (connectionId?: string) => void;
    onUserClipboard?: (observation: UserClipboardObservation) => void;
  }
>(function NoVncViewport(
  {
    sessionId,
    bindingEpoch,
    viewOnly = false,
    quality = 'SMOOTH',
    onConnectionState,
    onUnexpectedDisconnect,
    onConnectionId,
    onUserClipboard,
  },
  ref
) {
  const viewportRef = useRef<HTMLDivElement>(null);
  const clientRef = useRef<import('@novnc/novnc').default | undefined>(
    undefined
  );
  const onConnectionStateRef = useRef(onConnectionState);
  const onUnexpectedDisconnectRef = useRef(onUnexpectedDisconnect);
  const onConnectionIdRef = useRef(onConnectionId);
  const onUserClipboardRef = useRef(onUserClipboard);
  const qualityRef = useRef(quality);
  qualityRef.current = quality;
  const [state, setState] = useState<DesktopConnectionState>('CONNECTING');
  const [error, setError] = useState<string>();
  const [actorQuota, setActorQuota] = useState<{
    bitrateKbps: number;
    frameRateFps: number;
  }>();
  onConnectionStateRef.current = onConnectionState;
  onUnexpectedDisconnectRef.current = onUnexpectedDisconnect;
  onConnectionIdRef.current = onConnectionId;
  onUserClipboardRef.current = onUserClipboard;

  useImperativeHandle(
    ref,
    () => ({
      writeUserClipboard(value: string) {
        if (!clientRef.current || state !== 'CONNECTED' || viewOnly)
          return false;
        clientRef.current.clipboardPasteFrom(value);
        return true;
      },
    }),
    [state, viewOnly]
  );

  useEffect(() => {
    if (clientRef.current) {
      clientRef.current.qualityLevel = desktopQualityLevel[quality];
    }
  }, [quality]);

  useEffect(() => {
    const controller = new AbortController();
    let disposed = false;
    let client: import('@novnc/novnc').default | undefined;

    const transition = (next: DesktopConnectionState) => {
      if (disposed) return;
      setState(next);
      onConnectionStateRef.current?.(next);
    };

    const connect = async () => {
      transition('CONNECTING');
      setError(undefined);
      setActorQuota(undefined);
      try {
        const connection = await createRemoteDesktopConnection(
          sessionId,
          undefined,
          undefined,
          controller.signal,
          viewOnly
        );
        if (disposed || !viewportRef.current) return;
        if (connection.operationEpoch !== bindingEpoch) {
          throw new Error(
            '远程桌面票据属于过期 Session Context，请刷新后重试。'
          );
        }
        onConnectionIdRef.current?.(connection.connectionId);
        if (
          connection.actorBitrateLimitKbps &&
          connection.actorFrameRateLimitFps
        ) {
          setActorQuota({
            bitrateKbps: connection.actorBitrateLimitKbps,
            frameRateFps: connection.actorFrameRateLimitFps,
          });
        }
        const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const websocketUrl = new URL(
          connection.webSocketPath,
          `${scheme}//${window.location.host}`
        ).toString();
        const { default: RFB } = await import('@novnc/novnc');
        if (disposed || !viewportRef.current) return;
        client = new RFB(viewportRef.current, websocketUrl, {
          // Gateway 对协作连接做有界并发和独占接管仲裁；RFB shared flag 可避免
          // 新 Viewer 让 x11vnc 主动断开已有 Agent 辅助观察者。
          shared: true,
          wsProtocols: ['binary'],
        });
        clientRef.current = client;
        client.background = '#080d13';
        client.scaleViewport = true;
        client.resizeSession = false;
        client.clipViewport = true;
        client.dragViewport = false;
        client.focusOnClick = true;
        client.compressionLevel = 3;
        client.qualityLevel = desktopQualityLevel[qualityRef.current];
        client.viewOnly = connection.viewOnly;
        client.addEventListener('connect', () => transition('CONNECTED'), {
          once: true,
        });
        client.addEventListener('disconnect', (event) => {
          if (disposed) return;
          const clean = (event as RfbDisconnectEvent).detail.clean;
          transition('DISCONNECTED');
          if (!clean) {
            setError(
              '远程桌面连接意外中断，输入会被安全释放；Agent 会保持会话并在真人输入空闲后继续。'
            );
            onUnexpectedDisconnectRef.current?.();
          }
        });
        client.addEventListener('securityfailure', (event) => {
          if (disposed) return;
          const detail = (event as RfbSecurityFailureEvent).detail;
          setError(detail.reason || 'VNC 安全协商失败。');
          transition('FAILED');
        });
        client.addEventListener('clipboard', (event) => {
          if (disposed) return;
          const value = (event as RfbClipboardEvent).detail.text;
          if (!value || value.length > 2_000) return;
          onUserClipboardRef.current?.({
            value,
            observedAt: new Date().toISOString(),
          });
        });
      } catch (reason) {
        if (controller.signal.aborted || disposed) return;
        const message = isSessionApiError(reason)
          ? `${reason.body.message}${reason.body.requestId ? ` · ${reason.body.requestId}` : ''}`
          : reason instanceof Error
            ? reason.message
            : '无法建立远程桌面连接。';
        setError(message);
        transition('FAILED');
      }
    };

    void connect();
    return () => {
      disposed = true;
      controller.abort();
      client?.disconnect();
      clientRef.current = undefined;
      onConnectionIdRef.current?.(undefined);
    };
  }, [bindingEpoch, sessionId, viewOnly]);

  return (
    <div className="relative h-full min-h-[420px] overflow-hidden bg-[#080d13]">
      <div
        ref={viewportRef}
        className="novnc-viewport absolute inset-0 flex items-center justify-center overflow-hidden"
        aria-label="实时远程桌面画面"
      />
      {state !== 'CONNECTED' && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center bg-[#080d13]/94">
          <div className="max-w-sm px-8 text-center">
            {state === 'CONNECTING' ? (
              <LoaderCircle
                size={24}
                className="mx-auto animate-spin text-accent"
              />
            ) : (
              <MonitorOff size={24} className="mx-auto text-warning" />
            )}
            <p className="mt-4 text-[12px] font-medium text-text-primary">
              {state === 'CONNECTING'
                ? '正在协商 RFB 数据面'
                : '远程桌面不可用'}
            </p>
            <p className="mt-2 text-[10px] leading-5 text-text-muted">
              {error ||
                '正在签发单次连接票据，并建立同源 WebSocket 到 Browser Node。'}
            </p>
          </div>
        </div>
      )}
      {state === 'CONNECTED' && (
        <div className="pointer-events-none absolute left-3 top-3 inline-flex items-center gap-1.5 border border-success/25 bg-canvas/85 px-2 py-1 font-mono text-[9px] text-success">
          <Radio size={9} className="animate-pulse" />
          <span>RFB LIVE</span>
          <span>· {viewOnly ? 'VIEW ONLY' : 'SHARED CONTROL'}</span>
          {actorQuota && (
            <span>
              · ACTOR {actorQuota.bitrateKbps} Kbps / {actorQuota.frameRateFps}{' '}
              FPS 上限（非实测）
            </span>
          )}
        </div>
      )}
    </div>
  );
});
