/**
 * 按 id 合并下拉选项：远程搜索结果并入已有缓存，
 * 保证已选项的 label 与列表中的名称回显不因翻页/搜索而丢失。
 */
export function mergeById<T extends { id?: number }>(base: T[], incoming: T[]): T[] {
  const map = new Map<number, T>()
  base.forEach((item) => {
    if (item.id != null) map.set(item.id, item)
  })
  incoming.forEach((item) => {
    if (item.id != null) map.set(item.id, item)
  })
  return Array.from(map.values())
}
