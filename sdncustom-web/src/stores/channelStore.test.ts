import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useChannelStore } from './channelStore';
import { useBusinessStore } from './businessStore';
import { channelApi } from '../services/api';
import type { ChannelStatus } from '../types';

vi.mock('../services/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/api')>();
  return {
    ...actual,
    businessApi: {},
    channelApi: {
      getAll: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      delete: vi.fn(),
      connect: vi.fn(),
      disconnect: vi.fn(),
    },
    pointApi: {},
    dataApi: {},
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

const CH1 = { channelId: 'ch1', channelName: '通道一', status: 'DISCONNECTED' };
const CH2 = { channelId: 'ch2', channelName: '通道二', status: 'DISCONNECTED' };

describe('channelStore', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    localStorage.clear();
    useBusinessStore.setState({ currentBusinessId: null });
    useChannelStore.setState({ channels: [], loading: false, error: null });
  });

  it('fetchChannels 缺省按当前业务过滤', async () => {
    useBusinessStore.setState({ currentBusinessId: 'biz_x' });
    vi.mocked(channelApi.getAll).mockResolvedValue(ok([CH1, CH2]));

    await useChannelStore.getState().fetchChannels();

    expect(channelApi.getAll).toHaveBeenCalledWith('biz_x');
    expect(useChannelStore.getState().channels).toEqual([CH1, CH2]);
  });

  it('当前业务未解析（null）时不发请求', async () => {
    await useChannelStore.getState().fetchChannels();
    expect(channelApi.getAll).not.toHaveBeenCalled();
  });

  it('业务切换后过期响应被丢弃（seq 守卫）', async () => {
    const first = deferred<any>();
    const second = deferred<any>();
    vi.mocked(channelApi.getAll)
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);

    const p1 = useChannelStore.getState().fetchChannels('biz_1');
    const p2 = useChannelStore.getState().fetchChannels('biz_2');

    second.resolve(ok([CH2]));
    await p2;
    first.resolve(ok([CH1]));
    await p1;

    // 只有第二次（最新）的结果落库
    expect(useChannelStore.getState().channels).toEqual([CH2]);
    expect(useChannelStore.getState().loading).toBe(false);
  });

  it('后端返回非 200：记录 message', async () => {
    vi.mocked(channelApi.getAll).mockResolvedValue(fail(500, '查询失败'));

    await useChannelStore.getState().fetchChannels('biz_x');

    expect(useChannelStore.getState().error).toBe('查询失败');
    expect(useChannelStore.getState().channels).toEqual([]);
  });

  it('connectChannel 成功：重新拉取列表', async () => {
    useBusinessStore.setState({ currentBusinessId: 'biz_x' });
    vi.mocked(channelApi.connect).mockResolvedValue(ok(null));
    vi.mocked(channelApi.getAll).mockResolvedValue(ok([CH1]));

    await useChannelStore.getState().connectChannel('ch1');

    expect(channelApi.connect).toHaveBeenCalledWith('ch1');
    expect(channelApi.getAll).toHaveBeenCalledTimes(1);
    expect(useChannelStore.getState().error).toBeNull();
  });

  it('connectChannel 后端拒绝：error 记录并抛出', async () => {
    vi.mocked(channelApi.connect).mockResolvedValue(fail(400, '端口不可达'));

    await expect(useChannelStore.getState().connectChannel('ch1')).rejects.toThrow('端口不可达');
    expect(useChannelStore.getState().error).toBe('端口不可达');
  });

  it('disconnectChannel 网络异常：toErrorMessage 提取后端 message', async () => {
    vi.mocked(channelApi.disconnect).mockRejectedValue(
      Object.assign(new Error('Request failed with status code 500'), {
        isAxiosError: true,
        response: { data: { message: '通道状态冲突' } },
      })
    );

    await expect(useChannelStore.getState().disconnectChannel('ch1')).rejects.toThrow();
    expect(useChannelStore.getState().error).toBe('通道状态冲突');
  });

  it('updateStatus 只改目标通道的状态', () => {
    useChannelStore.setState({ channels: [{ ...CH1 }, { ...CH2 }] as any });

    useChannelStore.getState().updateStatus('ch1', 'CONNECTED' as ChannelStatus);

    const channels = useChannelStore.getState().channels;
    expect(channels[0].status).toBe('CONNECTED');
    expect(channels[1].status).toBe('DISCONNECTED');
  });
});
