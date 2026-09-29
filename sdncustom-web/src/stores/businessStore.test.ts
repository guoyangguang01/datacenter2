import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useBusinessStore } from './businessStore';
import { businessApi } from '../services/api';

vi.mock('../services/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/api')>();
  return {
    ...actual, // 保留真实的 toErrorMessage（axios 错误提取逻辑要真测）
    businessApi: {
      getAll: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      delete: vi.fn(),
    },
    channelApi: {},
    pointApi: {},
    dataApi: {},
  };
});

const STORAGE_KEY = 'sdncustom_business';

function ok<T>(data: T) {
  return { data: { code: 200, data } } as any;
}

function fail(code: number, message: string) {
  return { data: { code, message } } as any;
}

const B1 = { businessId: 'b1', businessName: '业务一' };
const B2 = { businessId: 'b2', businessName: '业务二' };

describe('businessStore', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    localStorage.clear();
    useBusinessStore.setState({
      businesses: [],
      currentBusinessId: null,
      loading: false,
      error: null,
    });
  });

  it('fetchBusinesses 成功：列表入库，无已存当前业务时回退到第一个', async () => {
    vi.mocked(businessApi.getAll).mockResolvedValue(ok([B1, B2]));

    await useBusinessStore.getState().fetchBusinesses();

    const state = useBusinessStore.getState();
    expect(state.businesses).toEqual([B1, B2]);
    expect(state.currentBusinessId).toBe('b1');
    expect(localStorage.getItem(STORAGE_KEY)).toBe('b1');
    expect(state.loading).toBe(false);
    expect(state.error).toBeNull();
  });

  it('fetchBusinesses 保留仍然有效的已存当前业务', async () => {
    useBusinessStore.setState({ currentBusinessId: 'b2' });
    vi.mocked(businessApi.getAll).mockResolvedValue(ok([B1, B2]));

    await useBusinessStore.getState().fetchBusinesses();

    expect(useBusinessStore.getState().currentBusinessId).toBe('b2');
  });

  it('fetchBusinesses 已存当前业务失效时回退到列表第一个并更新存储', async () => {
    useBusinessStore.setState({ currentBusinessId: 'deleted_biz' });
    vi.mocked(businessApi.getAll).mockResolvedValue(ok([B1, B2]));

    await useBusinessStore.getState().fetchBusinesses();

    expect(useBusinessStore.getState().currentBusinessId).toBe('b1');
    expect(localStorage.getItem(STORAGE_KEY)).toBe('b1');
  });

  it('列表为空时当前业务清空并移除存储', async () => {
    useBusinessStore.setState({ currentBusinessId: 'gone' });
    vi.mocked(businessApi.getAll).mockResolvedValue(ok([]));

    await useBusinessStore.getState().fetchBusinesses();

    expect(useBusinessStore.getState().currentBusinessId).toBeNull();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('后端返回非 200：记录 message，不改列表', async () => {
    vi.mocked(businessApi.getAll).mockResolvedValue(fail(500, '查询失败'));

    await useBusinessStore.getState().fetchBusinesses();

    const state = useBusinessStore.getState();
    expect(state.error).toBe('查询失败');
    expect(state.businesses).toEqual([]);
    expect(state.loading).toBe(false);
  });

  it('请求异常：错误信息落到 error', async () => {
    vi.mocked(businessApi.getAll).mockRejectedValue(new Error('网络断了'));

    await useBusinessStore.getState().fetchBusinesses();

    expect(useBusinessStore.getState().error).toBe('网络断了');
    expect(useBusinessStore.getState().loading).toBe(false);
  });

  it('setCurrentBusiness 更新状态并写 localStorage', () => {
    useBusinessStore.getState().setCurrentBusiness('b9');
    expect(useBusinessStore.getState().currentBusinessId).toBe('b9');
    expect(localStorage.getItem(STORAGE_KEY)).toBe('b9');
  });

  it('createBusiness 后端拒绝：error 记录并抛出', async () => {
    vi.mocked(businessApi.create).mockResolvedValue(fail(400, '业务已存在: b1'));

    await expect(
      useBusinessStore.getState().createBusiness({ businessId: 'b1' })
    ).rejects.toThrow('业务已存在: b1');
    expect(useBusinessStore.getState().error).toBe('业务已存在: b1');
  });

  it('createBusiness 成功：重新拉取列表', async () => {
    vi.mocked(businessApi.create).mockResolvedValue(ok(B1));
    vi.mocked(businessApi.getAll).mockResolvedValue(ok([B1]));

    await useBusinessStore.getState().createBusiness({ businessId: 'b1' });

    expect(businessApi.create).toHaveBeenCalledTimes(1);
    expect(businessApi.getAll).toHaveBeenCalledTimes(1);
    expect(useBusinessStore.getState().businesses).toEqual([B1]);
    expect(useBusinessStore.getState().error).toBeNull();
  });

  it('deleteBusiness 异常路径：toErrorMessage 兜出后端 message', async () => {
    vi.mocked(businessApi.delete).mockRejectedValue(
      Object.assign(new Error('Request failed with status code 500'), {
        isAxiosError: true,
        response: { data: { message: '该业务下还有 2 个通道，请先删除' } },
      })
    );

    await expect(useBusinessStore.getState().deleteBusiness('b1')).rejects.toThrow();
    expect(useBusinessStore.getState().error).toBe('该业务下还有 2 个通道，请先删除');
  });
});
