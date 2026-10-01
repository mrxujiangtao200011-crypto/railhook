import { http } from './http';
import type {
  AlertChannelResponse, AlertConditionResponse, AlertEventResponse, AlertRuleRequest, AlertRuleResponse,
  PageResponse,
} from '../types/api.types';

export const alertsApi = {
  channels: (): Promise<AlertChannelResponse[]> => http.get('/api/v1/alert-channels'),

  conditions: (): Promise<AlertConditionResponse[]> => http.get('/api/v1/alert-conditions'),

  listRules: (projectId: string): Promise<AlertRuleResponse[]> =>
    http.get(`/api/v1/projects/${projectId}/alerts/rules`),

  createRule: (projectId: string, data: AlertRuleRequest): Promise<AlertRuleResponse> =>
    http.post(`/api/v1/projects/${projectId}/alerts/rules`, data),

  updateRule: (projectId: string, ruleId: string, data: Partial<AlertRuleRequest>): Promise<AlertRuleResponse> =>
    http.put(`/api/v1/projects/${projectId}/alerts/rules/${ruleId}`, data),

  deleteRule: (projectId: string, ruleId: string): Promise<void> =>
    http.delete(`/api/v1/projects/${projectId}/alerts/rules/${ruleId}`),

  listEvents: (projectId: string, page = 0, size = 20): Promise<PageResponse<AlertEventResponse>> =>
    http.get(`/api/v1/projects/${projectId}/alerts/events?page=${page}&size=${size}`),

  unresolvedCount: (projectId: string): Promise<{ count: number }> =>
    http.get(`/api/v1/projects/${projectId}/alerts/events/unresolved-count`),

  resolveEvent: (projectId: string, eventId: string): Promise<void> =>
    http.post(`/api/v1/projects/${projectId}/alerts/events/${eventId}/resolve`),

  resolveAll: (projectId: string): Promise<{ resolved: number }> =>
    http.post(`/api/v1/projects/${projectId}/alerts/events/resolve-all`),
};
