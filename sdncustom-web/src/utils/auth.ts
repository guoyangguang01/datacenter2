const TOKEN_KEY = 'sdncustom_token';
const USERNAME_KEY = 'sdncustom_username';

export const authUtil = {
  getToken: (): string | null => localStorage.getItem(TOKEN_KEY),
  setToken: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clearToken: () => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USERNAME_KEY);
  },
  isAuthenticated: (): boolean => !!localStorage.getItem(TOKEN_KEY),
  getUsername: (): string | null => localStorage.getItem(USERNAME_KEY),
  setUsername: (username: string) => localStorage.setItem(USERNAME_KEY, username),
};
