import { request } from './request'
import { isMock, mockWarehouseList } from './mock'
import type { Warehouse, PageResult } from '@/types'

export function listWarehouses(
  keyword?: string,
  page = 1,
  size = 20,
  status?: string
): Promise<PageResult<Warehouse>> {
  if (isMock) return mockWarehouseList(keyword, page, size)
  return request<PageResult<Warehouse>>({
    url: '/api/warehouses',
    method: 'get',
    params: { keyword, page, size, status }
  })
}

export function createWarehouse(payload: Warehouse): Promise<Warehouse> {
  return request<Warehouse>({ url: '/api/warehouses', method: 'post', data: payload })
}

export function updateWarehouse(id: number, payload: Warehouse): Promise<Warehouse> {
  return request<Warehouse>({ url: `/api/warehouses/${id}`, method: 'put', data: payload })
}

export function deleteWarehouse(id: number): Promise<void> {
  return request<null>({ url: `/api/warehouses/${id}`, method: 'delete' }).then(() => undefined)
}
