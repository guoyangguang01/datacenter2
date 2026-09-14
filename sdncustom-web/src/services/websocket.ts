import { authUtil } from '../utils/auth';

type MessageHandler = (data: unknown) => void;

// 重连退避：1s 起、×2、封顶 30s（另加 30% 抖动）
const RECONNECT_BASE_MS = 1000;
const RECONNECT_MAX_MS = 30000;

class WebSocketService {
  private ws: WebSocket | null = null;
  private handlers: Map<string, Set<MessageHandler>> = new Map();
  private reconnectTimer: number | null = null;
  private shouldReconnect = true;
  private pendingMessages: string[] = [];
  private reconnectAttempts = 0;

  connect(): WebSocket | null {
    // Idempotent: reuse an existing connecting/open socket instead of leaking a new one.
    if (
      this.ws &&
      (this.ws.readyState === WebSocket.CONNECTING || this.ws.readyState === WebSocket.OPEN)
    ) {
      return this.ws;
    }

    const token = authUtil.getToken();
    if (!token) {
      console.warn('WebSocket connect skipped: not authenticated');
      return null;
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${protocol}//${window.location.host}/ws/data?token=${encodeURIComponent(token)}`;

    // A manual connect() means the caller wants the socket alive, so re-enable reconnection.
    this.shouldReconnect = true;

    const ws = new WebSocket(url);
    this.ws = ws;

    ws.onopen = () => {
      console.log('WebSocket connected');
      this.reconnectAttempts = 0; // 连上了就把退避重置
      this.emit('connected', null);
      this.flushPending();
    };

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        // Server pushes {type:'data', values:[...]} and {type:'channel_status', channelId, status}.
        this.emit(data.type, data);
      } catch (e) {
        console.error('Failed to parse WebSocket message', e);
      }
    };

    ws.onclose = () => {
      console.log('WebSocket disconnected');
      if (this.ws === ws) {
        this.ws = null;
      }
      this.emit('disconnected', null);
      if (this.shouldReconnect) {
        this.scheduleReconnect();
      }
    };

    ws.onerror = (error) => {
      console.error('WebSocket error', error);
    };

    return ws;
  }

  /**
   * 指数退避重连。原先固定 3s：服务端长时间不可用时会一直按 3s 打，
   * 而多个客户端又会在同一时刻一起重连。现在 1s 起、×2、封顶 30s，并加 30% 抖动。
   */
  private scheduleReconnect() {
    if (this.reconnectTimer !== null) return;
    if (!authUtil.isAuthenticated()) return;
    const base = Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS * 2 ** this.reconnectAttempts);
    const delay = Math.round(base * (1 + Math.random() * 0.3));
    this.reconnectAttempts += 1;
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, delay);
  }

  subscribe(channelIds: string[]) {
    this.send({ action: 'subscribe', channelIds });
  }

  unsubscribe(channelIds: string[]) {
    this.send({ action: 'unsubscribe', channelIds });
  }

  refresh(channelIds: string[]) {
    this.send({ action: 'refresh', channelIds });
  }

  private send(data: unknown) {
    const message = JSON.stringify(data);
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(message);
      return;
    }
    // Not open yet (CONNECTING / CLOSED / no socket): queue and flush on open.
    this.pendingMessages.push(message);
  }

  private flushPending() {
    if (this.pendingMessages.length === 0) return;
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    const pending = this.pendingMessages;
    this.pendingMessages = [];
    pending.forEach((msg) => {
      try {
        this.ws?.send(msg);
      } catch (e) {
        console.error('Failed to send pending WebSocket message', e);
      }
    });
  }

  on(event: string, handler: MessageHandler) {
    if (!this.handlers.has(event)) {
      this.handlers.set(event, new Set());
    }
    this.handlers.get(event)!.add(handler);
  }

  off(event: string, handler: MessageHandler) {
    this.handlers.get(event)?.delete(handler);
  }

  private emit(event: string, data: unknown) {
    this.handlers.get(event)?.forEach((handler) => handler(data));
  }

  disconnect() {
    this.shouldReconnect = false;
    this.reconnectAttempts = 0;
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    const ws = this.ws;
    this.ws = null;
    if (ws) {
      // Detach the close handler so a manual close cannot schedule a reconnect.
      ws.onclose = null;
      ws.close();
    }
  }
}

export const wsService = new WebSocketService();
