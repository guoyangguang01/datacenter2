import { describe, it, expect } from 'vitest';
import { toErrorMessage } from './api';

describe('toErrorMessage', () => {
  it('传输层错误优先取 response.data.message（e.message 只有状态码描述）', () => {
    const axiosLike = Object.assign(new Error('Request failed with status code 500'), {
      isAxiosError: true,
      response: { data: { message: '数据库连接失败' } },
    });
    expect(toErrorMessage(axiosLike, '兜底')).toBe('数据库连接失败');
  });

  it('普通 Error 取其 message', () => {
    expect(toErrorMessage(new Error('网络断了'), '兜底')).toBe('网络断了');
  });

  it('既不是 axios 错误也没有 message 时用兜底文案', () => {
    expect(toErrorMessage('有点问题', '兜底')).toBe('兜底');
    expect(toErrorMessage(new Error(''), '兜底')).toBe('兜底');
    expect(toErrorMessage(undefined, '兜底')).toBe('兜底');
  });
});
