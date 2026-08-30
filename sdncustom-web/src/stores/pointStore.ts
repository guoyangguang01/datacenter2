import { create } from 'zustand';
import type { MeasurementPoint, PointValue } from '../types';
import { pointApi } from '../services/api';
import { useBusinessStore } from './businessStore';

function toErrorMessage(e: unknown, fallback: string): string {
  if (e instanceof Error) return e.message;
  return fallback;
}

// Monotonic guards: only the most recent invocation commits its results.
let fetchAllValuesSeq = 0;
let fetchPointsSeq = 0;

interface PointStore {
  points: MeasurementPoint[];
  pointValues: Map<string, PointValue>;
  loading: boolean;
  error: string | null;
  fetchPoints: (channelId?: string, businessId?: string | null) => Promise<void>;
  fetchPointsForChannels: (channelIds: string[]) => Promise<void>;
  createPoint: (data: Partial<MeasurementPoint>) => Promise<void>;
  updatePoint: (id: string, data: Partial<MeasurementPoint>) => Promise<void>;
  deletePoint: (id: string) => Promise<void>;
  fetchValue: (pointId: string) => Promise<void>;
  fetchAllValues: () => Promise<void>;
  updateValue: (value: PointValue) => void;
  exportPoints: () => Promise<void>;
  importPoints: (data: Partial<MeasurementPoint>[]) => Promise<number>;
}

export const usePointStore = create<PointStore>((set, get) => ({
  points: [],
  pointValues: new Map(),
  loading: false,
  error: null,

  fetchPoints: async (channelId, businessId) => {
    // 缺省按当前业务过滤；业务尚未解析时不加载（页面以 currentBusinessId 为 effect 依赖）
    const biz = businessId === undefined ? useBusinessStore.getState().currentBusinessId : businessId;
    if (biz === null) return;
    const seq = ++fetchPointsSeq;
    set({ loading: true, error: null });
    try {
      const res = await pointApi.getAll(channelId, biz);
      if (seq !== fetchPointsSeq) return; // 业务/通道已切换，丢弃过期响应
      if (res.data.code !== 200) {
        set({ error: res.data.message || '加载测点失败' });
        return;
      }
      set({ points: res.data.data });
    } catch (e) {
      if (seq === fetchPointsSeq) set({ error: toErrorMessage(e, '加载测点失败') });
    } finally {
      if (seq === fetchPointsSeq) set({ loading: false });
    }
  },

  fetchPointsForChannels: async (channelIds) => {
    if (channelIds.length === 0) return;
    const seq = ++fetchPointsSeq;
    set({ loading: true, error: null });
    try {
      const results = await Promise.all(
        channelIds.map(async (cid) => {
          try {
            const res = await pointApi.getAll(cid);
            if (res.data.code !== 200) return [] as MeasurementPoint[];
            return res.data.data;
          } catch {
            return [] as MeasurementPoint[];
          }
        })
      );
      if (seq !== fetchPointsSeq) return; // stale response, discard
      // Merge by pointId (later wins), preserving previously loaded points from other channels.
      const merged = new Map<string, MeasurementPoint>();
      get().points.forEach((p) => merged.set(p.pointId, p));
      results.flat().forEach((p) => merged.set(p.pointId, p));
      set({ points: Array.from(merged.values()) });
    } finally {
      set({ loading: false });
    }
  },

  createPoint: async (data) => {
    set({ error: null });
    try {
      const res = await pointApi.create(data);
      if (res.data.code !== 200) {
        const msg = res.data.message || '创建测点失败';
        set({ error: msg });
        throw new Error(msg);
      }
      await get().fetchPoints();
    } catch (e) {
      if (!get().error) set({ error: toErrorMessage(e, '创建测点失败') });
      throw e;
    }
  },

  updatePoint: async (id, data) => {
    set({ error: null });
    try {
      const res = await pointApi.update(id, data);
      if (res.data.code !== 200) {
        const msg = res.data.message || '更新测点失败';
        set({ error: msg });
        throw new Error(msg);
      }
      await get().fetchPoints();
    } catch (e) {
      if (!get().error) set({ error: toErrorMessage(e, '更新测点失败') });
      throw e;
    }
  },

  deletePoint: async (id) => {
    set({ error: null });
    try {
      const res = await pointApi.delete(id);
      if (res.data.code !== 200) {
        const msg = res.data.message || '删除测点失败';
        set({ error: msg });
        throw new Error(msg);
      }
      await get().fetchPoints();
    } catch (e) {
      if (!get().error) set({ error: toErrorMessage(e, '删除测点失败') });
      throw e;
    }
  },

  fetchValue: async (pointId) => {
    try {
      const res = await pointApi.getValue(pointId);
      if (res.data.code !== 200) {
        set({ error: res.data.message || '读取测点值失败' });
        return;
      }
      const values = new Map(get().pointValues);
      values.set(pointId, res.data.data);
      set({ pointValues: values });
    } catch (e) {
      set({ error: toErrorMessage(e, '读取测点值失败') });
    }
  },

  fetchAllValues: async () => {
    const { points } = get();
    const seq = ++fetchAllValuesSeq;
    // Start from current map so failed per-point reads keep previously cached values.
    const values = new Map(get().pointValues);
    await Promise.all(
      points.map(async (p) => {
        try {
          const res = await pointApi.getValue(p.pointId);
          if (res.data.code !== 200) return;
          values.set(p.pointId, res.data.data);
        } catch {
          // ignore per-point failures
        }
      })
    );
    if (seq !== fetchAllValuesSeq) return; // stale response, discard
    set({ pointValues: values });
  },

  updateValue: (value) => {
    const values = new Map(get().pointValues);
    values.set(value.pointId, value);
    set({ pointValues: values });
  },

  exportPoints: async () => {
    const res = await pointApi.exportPoints();
    const blob = new Blob([res.data as BlobPart], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `points_export_${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
  },

  importPoints: async (data) => {
    set({ error: null });
    try {
      const res = await pointApi.importPoints(data);
      if (res.data.code !== 200) {
        const msg = res.data.message || '导入测点失败';
        set({ error: msg });
        throw new Error(msg);
      }
      await get().fetchPoints();
      return res.data.data?.length ?? 0;
    } catch (e) {
      if (!get().error) set({ error: toErrorMessage(e, '导入测点失败') });
      throw e;
    }
  },
}));
