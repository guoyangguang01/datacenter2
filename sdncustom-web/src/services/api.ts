import axios from 'axios';
import type { ApiResponse, Channel, MeasurementPoint, PointValue, SystemStatus } from '../types';
import { authUtil } from '../utils/auth';

const api = axios.create({
  baseURL: '/api',
  timeout: 10000,
});

api.interceptors.request.use((config) => {
  const token = authUtil.getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401 && window.location.pathname !== '/login') {
      authUtil.clearToken();
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

// Auth API
export const authApi = {
  login: (data: { username: string; password: string }) =>
    api.post<ApiResponse<{ token: string; username: string; expiresAt: number }>>('/auth/login', data),
  me: () => api.get<ApiResponse<{ username: string }>>('/auth/me'),
};

// System API
export const systemApi = {
  getStatus: () => api.get<ApiResponse<SystemStatus>>('/system/status'),
};

// Channel API
export const channelApi = {
  getAll: () => api.get<ApiResponse<Channel[]>>('/channels'),
  getById: (id: string) => api.get<ApiResponse<Channel>>(`/channels/${id}`),
  create: (data: Partial<Channel>) => api.post<ApiResponse<Channel>>('/channels', data),
  update: (id: string, data: Partial<Channel>) => api.put<ApiResponse<Channel>>(`/channels/${id}`, data),
  delete: (id: string) => api.delete<ApiResponse<void>>(`/channels/${id}`),
  connect: (id: string) => api.post<ApiResponse<void>>(`/channels/${id}/connect`),
  disconnect: (id: string) => api.post<ApiResponse<void>>(`/channels/${id}/disconnect`),
  exportAll: () => api.get('/channels/export', { responseType: 'blob' }),
  importAll: (data: { channels?: Partial<Channel>[]; points?: Partial<MeasurementPoint>[] }) =>
    api.post<{ channelCount: number; pointCount: number }>('/channels/import', data),
};

// MeasurementPoint API
export const pointApi = {
  getAll: (channelId?: string) => api.get<ApiResponse<MeasurementPoint[]>>('/points', { params: { channelId } }),
  getById: (id: string) => api.get<ApiResponse<MeasurementPoint>>(`/points/${id}`),
  create: (data: Partial<MeasurementPoint>) => api.post<ApiResponse<MeasurementPoint>>('/points', data),
  update: (id: string, data: Partial<MeasurementPoint>) => api.put<ApiResponse<MeasurementPoint>>(`/points/${id}`, data),
  delete: (id: string) => api.delete<ApiResponse<void>>(`/points/${id}`),
  getValue: (id: string) => api.get<ApiResponse<PointValue>>(`/points/${id}/value`),
  writeValue: (id: string, value: unknown) => api.put<ApiResponse<void>>(`/points/${id}/value`, { value }),
  exportPoints: () => api.get('/points/export', { responseType: 'blob' }),
  importPoints: (data: Partial<MeasurementPoint>[]) => api.post<ApiResponse<MeasurementPoint[]>>('/points/import', data),
};
