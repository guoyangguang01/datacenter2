import { create } from 'zustand';
import type { Channel } from '../types';
import { channelApi } from '../services/api';

interface ChannelStore {
  channels: Channel[];
  loading: boolean;
  fetchChannels: () => Promise<void>;
  createChannel: (data: Partial<Channel>) => Promise<void>;
  updateChannel: (id: string, data: Partial<Channel>) => Promise<void>;
  deleteChannel: (id: string) => Promise<void>;
  connectChannel: (id: string) => Promise<void>;
  disconnectChannel: (id: string) => Promise<void>;
}

export const useChannelStore = create<ChannelStore>((set, get) => ({
  channels: [],
  loading: false,

  fetchChannels: async () => {
    set({ loading: true });
    try {
      const res = await channelApi.getAll();
      set({ channels: res.data.data });
    } finally {
      set({ loading: false });
    }
  },

  createChannel: async (data) => {
    await channelApi.create(data);
    await get().fetchChannels();
  },

  updateChannel: async (id, data) => {
    await channelApi.update(id, data);
    await get().fetchChannels();
  },

  deleteChannel: async (id) => {
    await channelApi.delete(id);
    await get().fetchChannels();
  },

  connectChannel: async (id) => {
    await channelApi.connect(id);
    await get().fetchChannels();
  },

  disconnectChannel: async (id) => {
    await channelApi.disconnect(id);
    await get().fetchChannels();
  },
}));
