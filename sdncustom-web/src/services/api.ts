import axios from 'axios';
import type { ApiResponse, BusinessSystem, Channel, DataExportPayload, DataImportResult, MeasurementPoint, PointDirection, PointValue, SystemStatus } from '../types';
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

/**
 * 取错误里对用户有意义的信息。后端业务错误走 HTTP 200 + body.message（由各 store 判断 code），
 * 传输层/5xx 错误走 catch —— 那种情况下 e.message 只有 "Request failed with status code 500"，
 * 真正的原因在 response.data.message 里，这里把它取出来。
 */
export function toErrorMessage(e: unknown, fallback: string): string {
  if (axios.isAxiosError(e)) {
    const body = e.response?.data as { message?: string } | undefined;
    if (body?.message) return body.message;
  }
  if (e instanceof Error && e.message) return e.message;
  return fallback;
}

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

// Business API
export const businessApi = {
  getAll: () => api.get<ApiResponse<BusinessSystem[]>>('/businesses'),
  create: (data: Partial<BusinessSystem>) => api.post<ApiResponse<BusinessSystem>>('/businesses', data),
  update: (id: string, data: Partial<BusinessSystem>) => api.put<ApiResponse<BusinessSystem>>(`/businesses/${id}`, data),
  delete: (id: string) => api.delete<ApiResponse<void>>(`/businesses/${id}`),
};

// Channel API
export const channelApi = {
  getAll: (businessId?: string) => api.get<ApiResponse<Channel[]>>('/channels', { params: { businessId } }),
  getById: (id: string) => api.get<ApiResponse<Channel>>(`/channels/${id}`),
  create: (data: Partial<Channel>) => api.post<ApiResponse<Channel>>('/channels', data),
  update: (id: string, data: Partial<Channel>) => api.put<ApiResponse<Channel>>(`/channels/${id}`, data),
  delete: (id: string) => api.delete<ApiResponse<void>>(`/channels/${id}`),
  connect: (id: string) => api.post<ApiResponse<void>>(`/channels/${id}/connect`),
  disconnect: (id: string) => api.post<ApiResponse<void>>(`/channels/${id}/disconnect`),
};

// Data API — 只承载业务与测点；连接配置走 channelApi.importConfig
export const dataApi = {
  export: () => api.get('/data/export', { responseType: 'blob' }),
  import: (data: DataExportPayload) => api.post<ApiResponse<DataImportResult>>('/data/import', data),
  importCsv: (file: File, businessId: string, channelId: string) => {
    const form = new FormData();
    form.append('file', file);
    return api.post<ApiResponse<DataImportResult>>('/data/import-csv', form, {
      params: { businessId, channelId },
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
};

// MeasurementPoint API
export const pointApi = {
  getAll: (channelId?: string, businessId?: string, direction?: PointDirection) =>
    api.get<ApiResponse<MeasurementPoint[]>>('/points', { params: { channelId, businessId, direction } }),
  getById: (id: string) => api.get<ApiResponse<MeasurementPoint>>(`/points/${id}`),
  create: (data: Partial<MeasurementPoint>) => api.post<ApiResponse<MeasurementPoint>>('/points', data),
  update: (id: string, data: Partial<MeasurementPoint>) => api.put<ApiResponse<MeasurementPoint>>(`/points/${id}`, data),
  delete: (id: string) => api.delete<ApiResponse<void>>(`/points/${id}`),
  getValue: (id: string) => api.get<ApiResponse<PointValue>>(`/points/${id}/value`),
};
