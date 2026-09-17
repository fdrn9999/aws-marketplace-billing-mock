import { reactive, watch } from 'vue'

const STORAGE_KEY = 'marketplace-demo.customerId'

function readStored(): string {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? 'sub-usage-active'
  } catch {
    return 'sub-usage-active'
  }
}

/**
 * [데모 전용 로그인] 현재 선택한 고객 ID. 모든 앱 API 요청에 X-Customer-Id 헤더로 붙는다.
 * 실제 서비스에서는 로그인 세션이 이 역할을 한다.
 */
export const session = reactive({
  customerId: readStored(),
})

watch(
  () => session.customerId,
  (id) => {
    try {
      localStorage.setItem(STORAGE_KEY, id)
    } catch {
      // 저장소를 쓸 수 없는 환경(사생활 보호 모드 등)에서는 새로고침 시 기본 고객으로 돌아간다
    }
  },
)
