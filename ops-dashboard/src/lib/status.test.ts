import { describe, expect, it } from 'vitest'
import type { PaymentStatus } from '../api/types'
import { describe as describeStatus, isRepairable, isTerminal, needsAttention, toneOf } from './status'

const ALL: PaymentStatus[] = [
  'RECEIVED',
  'VALIDATED',
  'REJECTED',
  'SENT_TO_CLEARING',
  'CLEARED',
  'FAILED',
  'UNKNOWN',
  'MANUAL_REVIEW',
  'SETTLED',
]

describe('상태 의미', () => {
  it('종결 상태는 서버 전이표(REJECTED/FAILED/SETTLED)와 일치한다', () => {
    expect(ALL.filter(isTerminal)).toEqual(['REJECTED', 'FAILED', 'SETTLED'])
  })

  it('사람이 봐야 하는 것은 UNKNOWN 과 MANUAL_REVIEW 뿐이다', () => {
    expect(ALL.filter(needsAttention)).toEqual(['UNKNOWN', 'MANUAL_REVIEW'])
  })

  it('운영자가 직접 확정할 수 있는 것은 MANUAL_REVIEW 뿐이다', () => {
    expect(ALL.filter(isRepairable)).toEqual(['MANUAL_REVIEW'])
  })

  it('UNKNOWN 은 조치 대상이지만 아직 사람이 확정할 단계는 아니다', () => {
    expect(needsAttention('UNKNOWN')).toBe(true)
    expect(isRepairable('UNKNOWN')).toBe(false)
  })

  it('모든 상태에 색과 설명이 있다 — 화면에 빈칸이 남으면 운영자가 추측하게 된다', () => {
    for (const status of ALL) {
      expect(toneOf(status)).toBeTruthy()
      expect(describeStatus(status).length).toBeGreaterThan(0)
    }
  })

  it('UNKNOWN 을 실패라고 쓰지 않는다 — 화면의 낱말이 운영자의 판단을 만든다', () => {
    const text = describeStatus('UNKNOWN')
    expect(text).toContain('모름')
    expect(text).toContain('실패 아님')
  })

  it('돈이 나가지 않은 상태는 그렇다고 명시한다', () => {
    expect(describeStatus('FAILED')).toContain('돈이 나가지 않음')
    expect(describeStatus('REJECTED')).toContain('돈이 나가지 않음')
  })
})
