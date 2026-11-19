import { request } from './request'
import type { Store, StoreLicenseExpiry, PageResult } from '@/types'

export function listStores(
  keyword?: string,
  page = 1,
  size = 20,
  status?: string
): Promise<PageResult<Store>> {
  return request<PageResult<Store>>({
    url: '/api/stores',
    method: 'get',
    params: { keyword, page, size, status }
  })
}

export function getStore(id: number): Promise<Store> {
  return request<Store>({ url: `/api/stores/${id}`, method: 'get' })
}

export function createStore(payload: Store): Promise<Store> {
  return request<Store>({ url: '/api/stores', method: 'post', data: payload })
}

export function updateStore(id: number, payload: Store): Promise<Store> {
  return request<Store>({ url: `/api/stores/${id}`, method: 'put', data: payload })
}

export function deleteStore(id: number): Promise<void> {
  return request<null>({ url: `/api/stores/${id}`, method: 'delete' }).then(() => undefined)
}

// 证照临期门店（days 天内到期，含已过期）
export function listExpiringLicenses(days = 90, silent = false): Promise<StoreLicenseExpiry[]> {
  return request<StoreLicenseExpiry[]>({
    url: '/api/stores/expiring-licenses',
    method: 'get',
    params: { days },
    silent
  })
}
