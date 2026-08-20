/**
 * 백엔드 호출 (docs §10.2 — 오류 응답은 전부 RFC 9457 problem+json).
 *
 * 서버가 오류 코드와 사람이 읽을 문장을 이미 담아 보낸다. 그걸 버리고 "요청에 실패했습니다"로
 * 덮어쓰면, 운영자는 왜 거절됐는지 알아내려고 서버 로그를 뒤져야 한다. 그래서 problem+json 의
 * code/detail 을 그대로 화면까지 올린다.
 */

export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  code?: string
  errors?: { field: string; message: string }[]
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string | undefined
  readonly fieldErrors: { field: string; message: string }[]

  constructor(status: number, problem: ProblemDetail | null, fallback: string) {
    const parts = [problem?.detail ?? problem?.title ?? fallback]
    if (problem?.errors?.length) {
      parts.push(problem.errors.map((e) => `${e.field}: ${e.message}`).join(', '))
    }
    super(parts.filter(Boolean).join(' — '))
    this.name = 'ApiError'
    this.status = status
    this.code = problem?.code
    this.fieldErrors = problem?.errors ?? []
  }
}

interface RequestOptions {
  method?: string
  body?: unknown
  /** 운영 API 는 X-Operator 를 요구한다. 없으면 서버가 400 으로 거절한다. */
  operator?: string
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {}
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (options.operator) {
    headers['X-Operator'] = options.operator
  }

  const response = await fetch(path, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })

  if (!response.ok) {
    let problem: ProblemDetail | null = null
    try {
      problem = (await response.json()) as ProblemDetail
    } catch {
      // problem+json 이 아닌 오류(프록시 502 등). 상태 코드만으로 알려 준다.
      problem = null
    }
    throw new ApiError(response.status, problem, `${response.status} ${response.statusText}`)
  }

  if (response.status === 204) {
    return undefined as T
  }
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}
