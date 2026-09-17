import { ref, shallowRef } from 'vue'

/** 비동기 호출의 데이터/로딩/오류 상태를 한곳에서 다룬다. */
export function useAsync<T, A extends unknown[] = []>(fn: (...args: A) => Promise<T>) {
  const data = shallowRef<T | null>(null)
  const error = shallowRef<unknown>(null)
  const loading = ref(false)

  async function run(...args: A): Promise<T | null> {
    loading.value = true
    error.value = null
    try {
      const result = await fn(...args)
      data.value = result
      return result
    } catch (e) {
      error.value = e
      return null
    } finally {
      loading.value = false
    }
  }

  return { data, error, loading, run }
}
