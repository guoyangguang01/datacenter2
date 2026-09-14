import { create } from 'zustand';
import type { BusinessSystem } from '../types';
import { businessApi, toErrorMessage } from '../services/api';

const STORAGE_KEY = 'sdncustom_business';

interface BusinessStore {
  businesses: BusinessSystem[];
  currentBusinessId: string | null;
  loading: boolean;
  error: string | null;
  clearError: () => void;
  fetchBusinesses: () => Promise<void>;
  setCurrentBusiness: (businessId: string) => void;
  createBusiness: (data: Partial<BusinessSystem>) => Promise<void>;
  updateBusiness: (id: string, data: Partial<BusinessSystem>) => Promise<void>;
  deleteBusiness: (id: string) => Promise<void>;
}

export const useBusinessStore = create<BusinessStore>((set, get) => ({
  businesses: [],
  currentBusinessId: localStorage.getItem(STORAGE_KEY),
  loading: false,
  error: null,

  clearError: () => set({ error: null }),

  fetchBusinesses: async () => {
    set({ loading: true, error: null });
    try {
      const res = await businessApi.getAll();
      if (res.data.code !== 200) {
        set({ error: res.data.message || '加载业务失败' });
        return;
      }
      const businesses = res.data.data;
      // 校验已保存的当前业务仍存在；失效则回退到第一个
      let current = get().currentBusinessId;
      if (!current || !businesses.some((b) => b.businessId === current)) {
        current = businesses[0]?.businessId ?? null;
        if (current) {
          localStorage.setItem(STORAGE_KEY, current);
        } else {
          localStorage.removeItem(STORAGE_KEY);
        }
      }
      set({ businesses, currentBusinessId: current });
    } catch (e) {
      set({ error: toErrorMessage(e, '加载业务失败') });
    } finally {
      set({ loading: false });
    }
  },

  setCurrentBusiness: (businessId) => {
    localStorage.setItem(STORAGE_KEY, businessId);
    set({ currentBusinessId: businessId });
  },

  createBusiness: async (data) => {
    set({ error: null });
    try {
      const res = await businessApi.create(data);
      if (res.data.code !== 200) {
        const msg = res.data.message || '创建业务失败';
        set({ error: msg });
        throw new Error(msg);
      }
      await get().fetchBusinesses();
    } catch (e) {
      if (!get().error) set({ error: toErrorMessage(e, '创建业务失败') });
      throw e;
    }
  },

  updateBusiness: async (id, data) => {
    set({ error: null });
    try {
      const res = await businessApi.update(id, data);
      if (res.data.code !== 200) {
        const msg = res.data.message || '更新业务失败';
        set({ error: msg });
        throw new Error(msg);
      }
      await get().fetchBusinesses();
    } catch (e) {
      if (!get().error) set({ error: toErrorMessage(e, '更新业务失败') });
      throw e;
    }
  },

  deleteBusiness: async (id) => {
    set({ error: null });
    try {
      const res = await businessApi.delete(id);
      if (res.data.code !== 200) {
        const msg = res.data.message || '删除业务失败';
        set({ error: msg });
        throw new Error(msg);
      }
      await get().fetchBusinesses();
    } catch (e) {
      if (!get().error) set({ error: toErrorMessage(e, '删除业务失败') });
      throw e;
    }
  },
}));
