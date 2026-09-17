import { reactive } from 'vue'

import { api } from './api/endpoints'
import type { ClockView } from './api/types'

/** 헤더에 표시하는 시뮬레이션 시계. 시계를 옮기거나 데이터를 초기화한 뒤 refreshClock()을 호출한다. */
export const demo = reactive<{ clock: ClockView | null; clockError: boolean }>({
  clock: null,
  clockError: false,
})

export async function refreshClock(): Promise<void> {
  try {
    demo.clock = await api.clock()
    demo.clockError = false
  } catch {
    demo.clockError = true
  }
}
