import { request } from './request'
import { isMock, mockSupplierList } from './mock'
import type { Supplier, PageResult } from '@/types'

export function listSuppliers(
  keyword?: string,
  page = 1,
  size = 20,
  status?: string
): Promise<PageResult<Supplier>> {
  if (isMock) return mockSupplierList(keyword, page, size)
  return request<PageResult<Supplier>>({
    url: '/api/suppliers',
    method: 'get',
    params: { keyword, page, size, status }
  })
}

export function createSupplier(payload: Supplier): Promise<Supplier> {
  return request<Supplier>({ url: '/api/suppliers', method: 'post', data: payload })
}

export function updateSupplier(id: number, payload: Supplier): Promise<Supplier> {
  return request<Supplier>({ url: `/api/suppliers/${id}`, method: 'put', data: payload })
}

export function deleteSupplier(id: number): Promise<void> {
  return request<null>({ url: `/api/suppliers/${id}`, method: 'delete' }).then(() => undefined)
}
