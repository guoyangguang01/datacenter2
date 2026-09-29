import { describe, it, expect, beforeEach, afterAll, vi } from 'vitest';
import { usePointStore } from './pointStore';
import { useBusinessStore } from './businessStore';
import { pointApi, dataApi, businessApi } from '../services/api';

vi.mock('../services/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/api')>();
  return {
    ...actual,
    businessApi: {
      getAll: vi.fn(),
    },
    channelApi: {},
    pointApi: {
      getAll: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      delete: vi.fn(),
      getValue: vi.fn(),
    },
    dataApi: {
      export: vi.fn(),
      import: vi.fn(),
      importCsv: vi.fn(),
    },
  };
});

function ok<T>(data: T) {
  return { data: { code: 200, data } } as any;
}

function fail(code: number, message: string) {
  return { data: { code, message } } as any;
}

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const P1 = { pointId: 'p1', pointName: '点1', address: '40001', dataType: 'INT16' };
const P2 = { pointId: 'p2', pointName: '点2', address: '40002', dataType: 'FLOAT32' };

describe('pointStore', () => {
  // 导出用的浏览器 API 在 jsdom 里不存在，先补桩
  const originalCreateUrl = URL.createObjectURL;
  const originalRevokeUrl = URL.revokeObjectURL;
  const originalClick = HTMLAnchorElement.prototype.click;

  beforeEach(async () => {
    vi.resetAllMocks();
    localStorage.clear();
    URL.createObjectURL = vi.fn(() => 'blob:fake');
    URL.revokeObjectURL = vi.fn();
    HTMLAnchorElement.prototype.click = vi.fn();

    useBusinessStore.setState({ currentBusinessId: 'biz_a' });
    usePointStore.setState({
      points: [],
      pointValues: new Map(),
      loading: false,
      error: null,
    });

    // 预热：把模块内"points 当前属于哪个业务"的隐式状态钉在 biz_a，
    // 后续用例里 clearIfBusinessChanged 的清空行为才可预期
    vi.mocked(pointApi.getAll).mockResolvedValue(ok([]));
    await usePointStore.getState().fetchPoints();
    vi.resetAllMocks();
  });

  afterAll(() => {
    if (originalCreateUrl) URL.createObjectURL = originalCreateUrl;
    else Reflect.deleteProperty(URL, 'createObjectURL');
    if (originalRevokeUrl) URL.revokeObjectURL = originalRevokeUrl;
    else Reflect.deleteProperty(URL, 'revokeObjectURL');
    HTMLAnchorElement.prototype.click = originalClick;
  });

  it('fetchPoints：透传通道/业务/方向参数并入库', async () => {
    vi.mocked(pointApi.getAll).mockResolvedValue(ok([P1, P2]));

    await usePointStore.getState().fetchPoints('ch1', 'biz_1', 'OUTPUT');

    expect(pointApi.getAll).toHaveBeenCalledWith('ch1', 'biz_1', 'OUTPUT');
    expect(usePointStore.getState().points).toEqual([P1, P2]);
    expect(usePointStore.getState().loading).toBe(false);
  });

  it('businessId 显式为 null（业务未解析）时不发请求', async () => {
    await usePointStore.getState().fetchPoints(undefined, null);
    expect(pointApi.getAll).not.toHaveBeenCalled();
  });

  it('并发切换时过期响应被丢弃（seq 守卫）', async () => {
    const first = deferred<any>();
    const second = deferred<any>();
    vi.mocked(pointApi.getAll)
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);

    const p1 = usePointStore.getState().fetchPoints(undefined, 'biz_1');
    const p2 = usePointStore.getState().fetchPoints(undefined, 'biz_2');

    second.resolve(ok([P2]));
    await p2;
    first.resolve(ok([P1]));
    await p1;

    expect(usePointStore.getState().points).toEqual([P2]);
    expect(usePointStore.getState().loading).toBe(false);
  });

  it('切业务：新请求挂起期间旧业务的点立即清空（不残留界面）', async () => {
    vi.mocked(pointApi.getAll).mockResolvedValueOnce(ok([P1]));
    await usePointStore.getState().fetchPoints(undefined, 'biz_1');
    expect(usePointStore.getState().points).toHaveLength(1);

    const pending = deferred<any>();
    vi.mocked(pointApi.getAll).mockReturnValueOnce(pending.promise);
    const loading = usePointStore.getState().fetchPoints(undefined, 'biz_2');

    // 响应还没回来，但旧业务数据已经清掉
    expect(usePointStore.getState().points).toEqual([]);

    pending.resolve(ok([P2]));
    await loading;
    expect(usePointStore.getState().points).toEqual([P2]);
  });

  it('createPoint 后端拒绝：error 记录并抛出', async () => {
    vi.mocked(pointApi.create).mockResolvedValue(fail(400, 'address 重复'));

    await expect(
      usePointStore.getState().createPoint({ pointId: 'px' })
    ).rejects.toThrow('address 重复');
    expect(usePointStore.getState().error).toBe('address 重复');
  });

  it('createPoint 成功：重新加载当前业务的测点', async () => {
    vi.mocked(pointApi.create).mockResolvedValue(ok(P1));
    vi.mocked(pointApi.getAll).mockResolvedValue(ok([P1]));

    await usePointStore.getState().createPoint({ pointId: 'p1' });

    expect(pointApi.create).toHaveBeenCalledTimes(1);
    expect(pointApi.getAll).toHaveBeenCalledTimes(1);
    expect(usePointStore.getState().points).toEqual([P1]);
    expect(usePointStore.getState().error).toBeNull();
  });

  it('fetchValue：命中写入 pointValues；后端非 200 记 error', async () => {
    vi.mocked(pointApi.getValue)
      .mockResolvedValueOnce(ok({ pointId: 'p1', value: 42, timestamp: 1000 }))
      .mockResolvedValueOnce(fail(500, '缓存不可用'));

    await usePointStore.getState().fetchValue('p1');
    expect(usePointStore.getState().pointValues.get('p1')?.value).toBe(42);

    await usePointStore.getState().fetchValue('p1');
    expect(usePointStore.getState().error).toBe('缓存不可用');
  });

  it('fetchAllValues：REST 结果按时间戳合并，不覆盖等待期间到达的较新推送', async () => {
    usePointStore.setState({
      points: [{ pointId: 'a' }, { pointId: 'b' }] as any,
      pointValues: new Map([['a', { pointId: 'a', value: 'ws-newer', timestamp: 2000 } as any]]),
    });
    vi.mocked(pointApi.getValue)
      .mockResolvedValueOnce(ok({ pointId: 'a', value: 'rest-older', timestamp: 1000 }))
      .mockResolvedValueOnce(ok({ pointId: 'b', value: 'from-rest', timestamp: 500 }));

    await usePointStore.getState().fetchAllValues();

    const values = usePointStore.getState().pointValues;
    expect(values.get('a')?.value).toBe('ws-newer'); // 旧 REST 结果不能回滚新推送
    expect(values.get('b')?.value).toBe('from-rest');
  });

  it('fetchAllValues：单点失败被忽略，其余照常入库', async () => {
    usePointStore.setState({ points: [{ pointId: 'a' }, { pointId: 'b' }] as any });
    vi.mocked(pointApi.getValue)
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce(ok({ pointId: 'b', value: 1, timestamp: 1 }));

    await usePointStore.getState().fetchAllValues();

    const values = usePointStore.getState().pointValues;
    expect(values.has('a')).toBe(false);
    expect(values.get('b')?.value).toBe(1);
    expect(usePointStore.getState().error).toBeNull();
  });

  it('updateValue：写入 map', () => {
    usePointStore.getState().updateValue({ pointId: 'p9', value: 7, timestamp: 1 } as any);
    expect(usePointStore.getState().pointValues.get('p9')?.value).toBe(7);
  });

  it('fetchPointsForChannels：跨通道合并且按 pointId 去重；空列表不发请求', async () => {
    usePointStore.setState({ points: [{ ...P1, channelId: 'ch1' }] as any });
    vi.mocked(pointApi.getAll).mockResolvedValueOnce(ok([{ ...P1, channelId: 'ch9' }, P2]));

    await usePointStore.getState().fetchPointsForChannels(['ch2']);

    const points = usePointStore.getState().points;
    expect(points.map((p) => p.pointId).sort()).toEqual(['p1', 'p2']);
    // 后来的同 id 覆盖先前的
    expect(points.find((p) => p.pointId === 'p1')?.channelId).toBe('ch9');

    vi.mocked(pointApi.getAll).mockClear();
    await usePointStore.getState().fetchPointsForChannels([]);
    expect(pointApi.getAll).not.toHaveBeenCalled();
  });

  it('exportCsv：中文表头 + 方向中文化 + RFC4180 引号转义 + BOM', async () => {
    usePointStore.setState({
      points: [
        {
          pointId: 'p_1',
          pointName: '含,逗号',
          address: '40001',
          dataType: 'FLOAT32',
          unit: 'a"b',
          direction: 'OUTPUT',
          referencePointId: null,
          deadband: 0.5,
        },
        {
          pointId: 'p_2',
          pointName: '转发点',
          address: '40002',
          dataType: 'INT32',
          unit: null,
          direction: 'INPUT',
          referencePointId: 'p_1',
          deadband: null,
        },
      ] as any,
    });

    usePointStore.getState().exportCsv();

    expect(URL.createObjectURL).toHaveBeenCalledTimes(1);
    const blob = vi.mocked(URL.createObjectURL).mock.calls[0][0] as Blob;
    const text = await blob.text();

    // Blob.text() 按规范会剥掉首部 BOM，改查原始字节
    const head = new Uint8Array(await blob.arrayBuffer());
    expect([head[0], head[1], head[2]]).toEqual([0xef, 0xbb, 0xbf]); // Excel 中文不乱码
    expect(text).toContain('测点ID,测点名称,地址,数据类型,单位,测点方向,引用测点ID,死区');
    expect(text).toContain('p_1,"含,逗号",40001,FLOAT32,"a""b",输出,,0.5');
    expect(text).toContain('p_2,转发点,40002,INT32,,输入,p_1,');
  });

  it('exportCsv：没有测点时不产出文件', () => {
    usePointStore.getState().exportCsv();
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });

  it('importData：返回 pointCount 并刷新测点与业务', async () => {
    vi.mocked(dataApi.import).mockResolvedValue(ok({ pointCount: 3, businessCount: 1, channelCount: 1 }));
    vi.mocked(pointApi.getAll).mockResolvedValue(ok([P1]));
    vi.mocked(businessApi.getAll).mockResolvedValue(ok([]));

    const count = await usePointStore.getState().importData({ points: [] } as any);

    expect(count).toBe(3);
    expect(pointApi.getAll).toHaveBeenCalledTimes(1);
    expect(businessApi.getAll).toHaveBeenCalledTimes(1);
    expect(usePointStore.getState().points).toEqual([P1]);
  });

  it('importData 后端拒绝：error 记录并抛出', async () => {
    vi.mocked(dataApi.import).mockResolvedValue(fail(400, '缺少必填字段: direction'));

    await expect(usePointStore.getState().importData({ points: [] } as any)).rejects.toThrow(
      '缺少必填字段: direction'
    );
    expect(usePointStore.getState().error).toBe('缺少必填字段: direction');
  });
});
