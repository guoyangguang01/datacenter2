import { create } from 'zustand';
import type { DataExportPayload, MeasurementPoint, PointDirection, PointValue } from '../types';
import { dataApi, pointApi, toErrorMessage } from '../services/api';
import { useBusinessStore } from './businessStore';

// Monotonic guards: only the most recent invocation commits its results.
let fetchAllValuesSeq = 0;
let fetchPointsSeq = 0;

// 当前 points 属于哪个业务；undefined 表示尚未加载过
let pointsBusinessId: string | null | undefined = undefined;

/** 切业务时先清空 points/pointValues，否则上一业务的数据会残留在界面上 */
function clearIfBusinessChanged(
  businessId: string | null,
  set: (partial: { points: MeasurementPoint[]; pointValues: Map<string, PointValue> }) => void
): void {
  if (pointsBusinessId !== undefined && pointsBusinessId !== businessId) {
    set({ points: [], pointValues: new Map() });
  }
  pointsBusinessId = businessId;
}

/**
 * CSV 单元格（RFC 4180）：字段含逗号/引号/换行时用双引号包起来，字段内的 " 写成 ""。
 * 与后端 ImportFields.splitCsvLine 的解析规则成对——导出的文件能原样再导入。
 * null/undefined 输出空单元格（不是字面量 "null"），导入时映射回 null。
 */
function csvCell(value: string | number | null | undefined): string {
  if (value === null || value === undefined) return '';
  const s = String(value);
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/**
 * 测点方向导出成中文「输入/输出」——与外部数据源总表一致，也与页面上「输入测点/输出测点」
 * 的标签一致。导入器两种写法都认（见 ImportFields.parseDirection），所以回灌不受影响。
 */
function directionLabel(direction: PointDirection | undefined): string {
  if (direction === 'INPUT') return '输入';
  if (direction === 'OUTPUT') return '输出';
  return direction ?? '';
}

interface PointStore {
  points: MeasurementPoint[];
  pointValues: Map<string, PointValue>;
  loading: boolean;
  error: string | null;
  clearError: () => void;
  fetchPoints: (channelId?: string, businessId?: string | null, direction?: PointDirection) => Promise<void>;
  fetchPointsForChannels: (channelIds: string[]) => Promise<void>;
  createPoint: (data: Partial<MeasurementPoint>) => Promise<void>;
  updatePoint: (id: string, data: Partial<MeasurementPoint>) => Promise<void>;
  deletePoint: (id: string) => Promise<void>;
  fetchValue: (pointId: string) => Promise<void>;
  fetchAllValues: () => Promise<void>;
  updateValue: (value: PointValue) => void;
  exportData: () => Promise<void>;
  exportCsv: () => void;
  importData: (data: DataExportPayload) => Promise<number>;
  importCsv: (file: File, businessId: string, channelId: string) => Promise<number>;
}

export const usePointStore = create<PointStore>((set, get) => ({
  points: [],
  pointValues: new Map(),
  loading: false,
  error: null,

  clearError: () => set({ error: null }),

  fetchPoints: async (channelId, businessId, direction) => {
    // 缺省按当前业务过滤；业务尚未解析时不加载（页面以 currentBusinessId 为 effect 依赖）
    const biz = businessId === undefined ? useBusinessStore.getState().currentBusinessId : businessId;
    if (biz === null) return;
    clearIfBusinessChanged(biz, set);
    const seq = ++fetchPointsSeq;
    set({ loading: true, error: null });
    try {
      const res = await pointApi.getAll(channelId, biz, direction);
      if (seq !== fetchPointsSeq) return; // 业务/通道/方向已切换，丢弃过期响应
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
    // 这里按通道取（不带业务过滤），但仍要知道业务是否变了——否则监控页会跨业务累积测点
    clearIfBusinessChanged(useBusinessStore.getState().currentBusinessId, set);
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
    const fetched = new Map<string, PointValue>();
    await Promise.all(
      points.map(async (p) => {
        try {
          const res = await pointApi.getValue(p.pointId);
          if (res.data.code !== 200) return;
          fetched.set(p.pointId, res.data.data);
        } catch {
          // ignore per-point failures
        }
      })
    );
    if (seq !== fetchAllValuesSeq) return; // stale response, discard
    // 以当前 map 为基础按时间戳合并：等待期间到达的 WS 推送不能被这轮 REST 结果覆盖
    const merged = new Map(get().pointValues);
    fetched.forEach((value, pointId) => {
      const existing = merged.get(pointId);
      if (!existing || (value.timestamp ?? 0) >= (existing.timestamp ?? 0)) {
        merged.set(pointId, value);
      }
    });
    set({ pointValues: merged });
  },

  updateValue: (value) => {
    const values = new Map(get().pointValues);
    values.set(value.pointId, value);
    set({ pointValues: values });
  },

  exportData: async () => {
    const res = await dataApi.export();
    const blob = new Blob([res.data as BlobPart], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `sdncustom_data_${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(url);
  },

  exportCsv: () => {
    const { points } = get();
    if (points.length === 0) return;

    // 中文表头与测点管理页的列名一致；顺序同后端 ImportFields 的 CSV 契约。
    // 导入器认得这些中文别名，所以导出的文件能直接再导入
    const header = '测点ID,测点名称,地址,数据类型,单位,测点方向,引用测点ID,死区';
    const rows = points.map((p) =>
      [
        p.pointId,
        p.pointName,
        p.address,
        p.dataType,
        p.unit,
        directionLabel(p.direction),
        p.referencePointId,
        p.deadband,
      ]
        .map(csvCell)
        .join(',')
    );

    // BOM 前置，否则 Excel 打开中文乱码（与 MonitorPage 导出日志同一约定）
    const blob = new Blob(['\uFEFF' + [header, ...rows].join('\n')], {
      type: 'text/csv;charset=utf-8;',
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `points_${new Date().toISOString().slice(0, 19).replace(/:/g, '-')}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  },

  importData: async (data) => {
    set({ error: null });
    try {
      const res = await dataApi.import(data);
      if (res.data.code !== 200) {
        const msg = res.data.message || '导入数据失败';
        set({ error: msg });
        throw new Error(msg);
      }
      await get().fetchPoints();
      await useBusinessStore.getState().fetchBusinesses();
      return res.data.data?.pointCount ?? 0;
    } catch (e) {
      if (!get().error) set({ error: toErrorMessage(e, '导入数据失败') });
      throw e;
    }
  },

  importCsv: async (file, businessId, channelId) => {
    set({ error: null });
    try {
      const res = await dataApi.importCsv(file, businessId, channelId);
      if (res.data.code !== 200) {
        const msg = res.data.message || 'CSV 导入失败';
        set({ error: msg });
        throw new Error(msg);
      }
      await get().fetchPoints();
      return res.data.data?.pointCount ?? 0;
    } catch (e) {
      if (!get().error) set({ error: toErrorMessage(e, 'CSV 导入失败') });
      throw e;
    }
  },
}));
