import { create } from 'zustand';
import type { MeasurementPoint, PointValue } from '../types';
import { pointApi } from '../services/api';

interface PointStore {
  points: MeasurementPoint[];
  pointValues: Map<string, PointValue>;
  loading: boolean;
  fetchPoints: (channelId?: string) => Promise<void>;
  createPoint: (data: Partial<MeasurementPoint>) => Promise<void>;
  updatePoint: (id: string, data: Partial<MeasurementPoint>) => Promise<void>;
  deletePoint: (id: string) => Promise<void>;
  fetchValue: (pointId: string) => Promise<void>;
  fetchAllValues: () => Promise<void>;
  updateValue: (value: PointValue) => void;
}

export const usePointStore = create<PointStore>((set, get) => ({
  points: [],
  pointValues: new Map(),
  loading: false,

  fetchPoints: async (channelId) => {
    set({ loading: true });
    try {
      const res = await pointApi.getAll(channelId);
      set({ points: res.data.data });
    } finally {
      set({ loading: false });
    }
  },

  createPoint: async (data) => {
    await pointApi.create(data);
    await get().fetchPoints();
  },

  updatePoint: async (id, data) => {
    await pointApi.update(id, data);
    await get().fetchPoints();
  },

  deletePoint: async (id) => {
    await pointApi.delete(id);
    await get().fetchPoints();
  },

  fetchValue: async (pointId) => {
    const res = await pointApi.getValue(pointId);
    const values = new Map(get().pointValues);
    values.set(pointId, res.data.data);
    set({ pointValues: values });
  },

  fetchAllValues: async () => {
    const { points } = get();
    const values = new Map<string, PointValue>();
    await Promise.all(
      points.map(async (p) => {
        try {
          const res = await pointApi.getValue(p.pointId);
          values.set(p.pointId, res.data.data);
        } catch {
          // ignore
        }
      })
    );
    set({ pointValues: values });
  },

  updateValue: (value) => {
    const values = new Map(get().pointValues);
    values.set(value.pointId, value);
    set({ pointValues: values });
  },
}));
