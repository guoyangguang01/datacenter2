type MessageHandler = (data: unknown) => void;

class WebSocketService {
  private ws: WebSocket | null = null;
  private handlers: Map<string, Set<MessageHandler>> = new Map();
  private reconnectTimer: number | null = null;
  private shouldReconnect = true;
  private pendingMessages: string[] = [];

  connect(): WebSocket | null {
    // Idempotent: reuse an existing connecting/open socket instead of leaking a new one.
    if (
      this.ws &&
      (this.ws.readyState === WebSocket.CONNECTING || this.ws.readyState === WebSocket.OPEN)
    ) {
      return this.ws;
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${protocol}//${window.location.host}/ws/data`;

    // A manual connect() means the caller wants the socket alive, so re-enable reconnection.
    this.shouldReconnect = true;

    const ws = new WebSocket(url);
    this.ws = ws;

    ws.onopen = () => {
      console.log('WebSocket connected');
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

  private scheduleReconnect() {
    if (this.reconnectTimer !== null) return;
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, 3000);
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
