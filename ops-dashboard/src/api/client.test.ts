import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from './client'

function respondWith(status: number, body: unknown, contentType = 'application/problem+json') {
  return vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    statusText: 'ERR',
    headers: new Headers({ 'content-type': contentType }),
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response)
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('request', () => {
  it('성공 응답은 그대로 파싱해 돌려준다', async () => {
    vi.stubGlobal('fetch', respondWith(200, { paymentId: 'P1' }, 'application/json'))

    await expect(request<{ paymentId: string }>('/api/v1/payments/P1')).resolves.toEqual({
      paymentId: 'P1',
    })
  })

  it('problem+json 의 code 와 detail 을 그대로 올린다 — 서버가 준 이유를 버리지 않는다', async () => {
    vi.stubGlobal(
      'fetch',
      respondWith(409, {
        status: 409,
        title: '해당 결제는 이 방식으로 처리할 수 없습니다.',
        detail: 'FAILED 상태는 운영자가 CLEARED 로 바꿀 수 없습니다.',
        code: 'PC-O001',
      }),
    )

    const error = await request('/api/v1/ops/payments/P1/repair', { method: 'POST' }).catch((e) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(409)
    expect((error as ApiError).code).toBe('PC-O001')
    expect((error as ApiError).message).toContain('CLEARED 로 바꿀 수 없습니다')
  })

  it('필드 오류도 함께 보여 준다', async () => {
    vi.stubGlobal(
      'fetch',
      respondWith(400, {
        code: 'PC-V001',
        detail: '요청 본문 검증 실패',
        errors: [{ field: 'reason', message: 'must not be blank' }],
      }),
    )

    const error = (await request('/x', { method: 'POST' }).catch((e) => e)) as ApiError

    expect(error.fieldErrors).toEqual([{ field: 'reason', message: 'must not be blank' }])
    expect(error.message).toContain('reason: must not be blank')
  })

  it('problem+json 이 아닌 오류(프록시 502 등)도 상태 코드로 알려 준다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 502,
        statusText: 'Bad Gateway',
        json: async () => {
          throw new Error('not json')
        },
        text: async () => '<html>',
      } as unknown as Response),
    )

    const error = (await request('/x').catch((e) => e)) as ApiError

    expect(error.status).toBe(502)
    expect(error.code).toBeUndefined()
    expect(error.message).toContain('502')
  })

  it('운영자 식별자를 헤더로 싣는다 — 없으면 서버가 거절한다', async () => {
    const fetchMock = respondWith(200, {}, 'application/json')
    vi.stubGlobal('fetch', fetchMock)

    await request('/api/v1/ops/dead-letters/D1/republish', {
      method: 'POST',
      operator: 'kim.ops',
      body: { reason: '원인 확인함' },
    })

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect((init.headers as Record<string, string>)['X-Operator']).toBe('kim.ops')
    expect(init.body).toBe('{"reason":"원인 확인함"}')
  })

  it('GET 에는 Content-Type 을 붙이지 않는다', async () => {
    const fetchMock = respondWith(200, [], 'application/json')
    vi.stubGlobal('fetch', fetchMock)

    await request('/api/v1/ops/worklist')

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect((init.headers as Record<string, string>)['Content-Type']).toBeUndefined()
  })
})
