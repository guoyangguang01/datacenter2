import { create } from 'zustand';
import type { Channel, ChannelStatus } from '../types';
import { channelApi } from '../services/api';

function toErrorMessage(e: unknown, fallback: string): string {
  if (e instanceof Error) return e.message;
  return fallback;
}

interface ChannelStore {
  channels: Channel[];
  loading: boolean;
  error: string | null;
  fetchChannels: () => Promise<void>;
  createChannel: (data: Partial<Channel>) => Promise<void>;
  updateChannel: (id: string, data: Partial<Channel>) => Promise<void>;
  deleteChannel: (id: string) => Promise<void>;
  connectChannel: (id: string) => Promise<void>;
  disconnectChannel: (id: string) => Promise<void>;
  updateStatus: (channelId: string, status: ChannelStatus) => void;
}

export const useChannelStore = create<ChannelStore>((set, get) => ({
  channels: [],
  loading: false,
  error: null,

  fetchChannels: async () => {
    set({ loading: true, error: null });
    try {
      const res = await channelApi.getAll();
      if (res.data.code !== 200) {
        set({ error: res.data.message || '加载通道失败' });
        return;
      }
      set({ channels: res.data.data });
    } catch (e) {
      set({ error: toErrorMessage(e, '加载通道失败') });
    } finally {
      set({ loading: false });
    }
  },

  createChannel: async (data) => {
    set({ error: null });
    try {
      const res = await channelApi.create(data);
      if (res.data.code !== 200) {
        const msg = res.data.message || '创建通道失败';
        set({ error: msg });
        throw new Error(msg);
      }
      await get().fetchChannels();
    } catch (e) {
      if (!get().error) set({ error: toErrorMessage(e, '创建通道失败') });
      throw e;
    }
  },

  updateChannel: async (id, data) => {
    set({ error: null });
    try {
      const res = await channelApi.update(id, data);
      if (res.data.code !== 200) {
        const msg = res.data.message || '更新通道失败';
        set({ error: msg });
        throw new Error(msg);
      }
      await get().fetchChannels();
    } catch (e) {
      if (!get().error) set({ error: toErrorMessage(e, '更新通道失败') });
      throw e;
    }
  },

  deleteChannel: async (id) => {
    set({ error: null });
    try {
      const res = await channelApi.delete(id);
      if (res.data.code !== 200) {
        const msg = res.data.message || '删除通道失败';
        set({ error: msg });
        throw new Error(msg);
      }
      await get().fetchChannels();
    } catch (e) {
      if (!get().error) set({ error: toErrorMessage(e, '删除通道失败') });
      throw e;
    }
  },

  connectChannel: async (id) => {
    set({ error: null });
    try {
      const res = await channelApi.connect(id);
      if (res.data.code !== 200) {
        const msg = res.data.message || '连接失败';
        set({ error: msg });
        throw new Error(msg);
      }
      await get().fetchChannels();
    } catch (e) {
      if (!get().error) set({ error: toErrorMessage(e, '连接失败') });
      throw e;
    }
  },

  disconnectChannel: async (id) => {
    set({ error: null });
    try {
      const res = await channelApi.disconnect(id);
      if (res.data.code !== 200) {
        const msg = res.data.message || '断开失败';
        set({ error: msg });
        throw new Error(msg);
      }
      await get().fetchChannels();
    } catch (e) {
      if (!get().error) set({ error: toErrorMessage(e, '断开失败') });
      throw e;
    }
  },

  updateStatus: (channelId, status) => {
    const channels = get().channels.map((c) =>
      c.channelId === channelId ? { ...c, status } : c
    );
    set({ channels });
  },
}));
