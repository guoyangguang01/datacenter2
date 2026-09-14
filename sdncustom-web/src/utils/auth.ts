const TOKEN_KEY = 'sdncustom_token';
const USERNAME_KEY = 'sdncustom_username';

/** 解析 JWT payload 的 exp（秒）；解析不出来返回 null */
function tokenExpiry(token: string): number | null {
  try {
    const part = token.split('.')[1];
    if (!part) return null;
    const base64 = part.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const payload = JSON.parse(atob(padded)) as { exp?: number };
    return typeof payload.exp === 'number' ? payload.exp : null;
  } catch {
    return null;
  }
}

function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USERNAME_KEY);
}

export const authUtil = {
  getToken: (): string | null => localStorage.getItem(TOKEN_KEY),
  setToken: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clearToken,
  /**
   * 不只是"有没有 token"——过期的 token 也要算未登录，
   * 否则 ProtectedRoute 和 WS 重连会拿着废 token 一直转。
   */
  isAuthenticated: (): boolean => {
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) return false;
    const exp = tokenExpiry(token);
    if (exp !== null && exp * 1000 <= Date.now()) {
      clearToken();
      return false;
    }
    return true;
  },
  getUsername: (): string | null => localStorage.getItem(USERNAME_KEY),
  setUsername: (username: string) => localStorage.setItem(USERNAME_KEY, username),
};
