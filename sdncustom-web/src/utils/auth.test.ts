import { describe, it, expect, beforeEach } from 'vitest';
import { authUtil } from './auth';

const TOKEN_KEY = 'sdncustom_token';
const USERNAME_KEY = 'sdncustom_username';

/** 拼一个只含 payload 的伪 JWT（签名段随便填，authUtil 不校验签名） */
function makeJwt(payload: object): string {
  const encode = (o: object) =>
    btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode(payload)}.sig`;
}

describe('authUtil', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('token 与用户名存取往返', () => {
    authUtil.setToken('abc.def.ghi');
    authUtil.setUsername('admin');
    expect(authUtil.getToken()).toBe('abc.def.ghi');
    expect(authUtil.getUsername()).toBe('admin');
  });

  it('没有 token 判定为未登录', () => {
    expect(authUtil.isAuthenticated()).toBe(false);
  });

  it('未过期的 token 判定为已登录', () => {
    authUtil.setToken(makeJwt({ exp: Math.floor(Date.now() / 1000) + 3600 }));
    expect(authUtil.isAuthenticated()).toBe(true);
    // 仍然在位，没被误清
    expect(authUtil.getToken()).not.toBeNull();
  });

  it('已过期的 token 判定为未登录并顺手清除', () => {
    authUtil.setToken(makeJwt({ exp: Math.floor(Date.now() / 1000) - 10 }));
    authUtil.setUsername('admin');

    expect(authUtil.isAuthenticated()).toBe(false);
    // 过期 token 留着只会让 ProtectedRoute / WS 重连一直拿废 token 空转
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(USERNAME_KEY)).toBeNull();
  });

  it('payload 没有 exp 声明时按未过期处理（不误杀）', () => {
    authUtil.setToken(makeJwt({ sub: 'someone' }));
    expect(authUtil.isAuthenticated()).toBe(true);
  });

  it('clearToken 清除凭证', () => {
    authUtil.setToken('x');
    authUtil.setUsername('y');
    authUtil.clearToken();
    expect(authUtil.getToken()).toBeNull();
    expect(authUtil.getUsername()).toBeNull();
  });
});
