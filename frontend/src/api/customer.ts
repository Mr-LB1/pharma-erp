import { request } from './request'
import { isMock, mockCustomerList } from './mock'
import type { Customer, PageResult } from '@/types'

export function listCustomers(
  keyword?: string,
  page = 1,
  size = 20,
  status?: string
): Promise<PageResult<Customer>> {
  if (isMock) return mockCustomerList(keyword, page, size)
  return request<PageResult<Customer>>({
    url: '/api/customers',
    method: 'get',
    params: { keyword, page, size, status }
  })
}

export function createCustomer(payload: Customer): Promise<Customer> {
  return request<Customer>({ url: '/api/customers', method: 'post', data: payload })
}

export function updateCustomer(id: number, payload: Customer): Promise<Customer> {
  return request<Customer>({ url: `/api/customers/${id}`, method: 'put', data: payload })
}

export function deleteCustomer(id: number): Promise<void> {
  return request<null>({ url: `/api/customers/${id}`, method: 'delete' }).then(() => undefined)
}
