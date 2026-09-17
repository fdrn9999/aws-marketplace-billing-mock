import { ref, shallowRef } from 'vue'

/**
 * 비동기 호출의 데이터/로딩/오류 상태를 한곳에서 다룬다.
 * 여러 번 호출하면 마지막 호출의 결과만 반영한다 (늦게 도착한 이전 응답이 화면을 덮지 않도록).
 */
export function useAsync<T, A extends unknown[] = []>(fn: (...args: A) => Promise<T>) {
  const data = shallowRef<T | null>(null)
  const error = shallowRef<unknown>(null)
  const loading = ref(false)
  let latest = 0

  async function run(...args: A): Promise<T | null> {
    const seq = ++latest
    loading.value = true
    error.value = null
    try {
      const result = await fn(...args)
      if (seq !== latest) return null
      data.value = result
      return result
    } catch (e) {
      if (seq === latest) error.value = e
      return null
    } finally {
      if (seq === latest) loading.value = false
    }
  }

  /** 진행 중인 호출의 결과를 버리고 상태를 비운다 */
  function reset() {
    latest++
    data.value = null
    error.value = null
    loading.value = false
  }

  return { data, error, loading, run, reset }
}
