import axios from 'axios';
import type { ApiResponse, Channel, MeasurementPoint, PointValue } from '../types';

const api = axios.create({
  baseURL: '/api',
  timeout: 10000,
});

// Channel API
export const channelApi = {
  getAll: () => api.get<ApiResponse<Channel[]>>('/channels'),
  getById: (id: string) => api.get<ApiResponse<Channel>>(`/channels/${id}`),
  create: (data: Partial<Channel>) => api.post<ApiResponse<Channel>>('/channels', data),
  update: (id: string, data: Partial<Channel>) => api.put<ApiResponse<Channel>>(`/channels/${id}`, data),
  delete: (id: string) => api.delete<ApiResponse<void>>(`/channels/${id}`),
  connect: (id: string) => api.post<ApiResponse<void>>(`/channels/${id}/connect`),
  disconnect: (id: string) => api.post<ApiResponse<void>>(`/channels/${id}/disconnect`),
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
};
