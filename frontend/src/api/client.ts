import type { ApiErrorBody } from './types'
import { session } from '../session'

/** 백엔드가 돌려준 오류(또는 네트워크 오류)를 화면에서 다루기 쉬운 형태로 감싼다. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details: Record<string, unknown>
  readonly requestId?: string

  constructor(status: number, code: string, message: string, details: Record<string, unknown> = {}, requestId?: string) {
    super(message)
    this.status = status
    this.code = code
    this.details = details
    this.requestId = requestId
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT'
  body?: unknown
  headers?: Record<string, string>
  /** true면 X-Customer-Id 헤더를 붙이지 않는다 */
  anonymous?: boolean
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (!options.anonymous && session.customerId) {
    headers['X-Customer-Id'] = session.customerId
  }

  let res: Response
  try {
    res = await fetch(path, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
  } catch (e) {
    throw new ApiError(0, 'NETWORK_ERROR', `서버에 연결할 수 없습니다 (${(e as Error).message})`)
  }

  const text = await res.text()
  let data: unknown = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = null
    }
  }
  if (!res.ok) {
    const appError = (data as ApiErrorBody | null)?.error
    if (appError?.code) {
      throw new ApiError(res.status, appError.code, appError.message, appError.details ?? {}, appError.requestId)
    }
    // Mock AWS API는 AWS 형식({__type, message})으로 오류를 돌려준다
    const awsError = data as { __type?: string; message?: string } | null
    if (awsError?.__type) {
      throw new ApiError(res.status, awsError.__type, awsError.message ?? awsError.__type)
    }
    throw new ApiError(res.status, `HTTP_${res.status}`, `요청이 실패했습니다 (HTTP ${res.status})`)
  }
  return data as T
}
