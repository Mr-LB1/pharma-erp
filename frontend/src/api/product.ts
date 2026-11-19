import { request } from './request'
import { isMock, mockProductList, mockProductCreate, mockProductUpdate } from './mock'
import type { Product, PageResult } from '@/types'

export function listProducts(
  keyword?: string,
  page = 1,
  size = 20,
  status?: string
): Promise<PageResult<Product>> {
  if (isMock) return mockProductList(keyword, page, size)
  return request<PageResult<Product>>({
    url: '/api/products',
    method: 'get',
    params: { keyword, page, size, status }
  })
}

export function createProduct(payload: Product): Promise<Product> {
  if (isMock) return mockProductCreate(payload)
  return request<Product>({
    url: '/api/products',
    method: 'post',
    data: payload
  })
}

export function updateProduct(id: number, payload: Product): Promise<Product> {
  if (isMock) return mockProductUpdate(id, payload)
  return request<Product>({
    url: `/api/products/${id}`,
    method: 'put',
    data: payload
  })
}

export function deleteProduct(id: number): Promise<void> {
  return request<null>({ url: `/api/products/${id}`, method: 'delete' }).then(() => undefined)
}
