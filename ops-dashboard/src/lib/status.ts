import type { PaymentStatus } from '../api/types'

/**
 * 상태의 의미 (docs §4.2 상태머신).
 *
 * 백엔드 전이표를 화면 쪽에 복사한 것이 아니다. 화면이 알아야 하는 것은 전이 규칙이 아니라
 * <b>"이 건은 사람이 봐야 하는가"</b> 하나뿐이고, 그 판단 기준만 여기 둔다.
 * 전이 가능 여부는 서버가 판정하며, 화면은 서버의 거절(409)을 그대로 보여 준다.
 */

/** 더 이상 움직이지 않는 상태. */
const TERMINAL: ReadonlySet<PaymentStatus> = new Set(['REJECTED', 'FAILED', 'SETTLED'])

/** 사람이 봐야 하는 상태. 워크리스트의 정의이자 알림 규칙과 짝을 이룬다. */
const NEEDS_ATTENTION: ReadonlySet<PaymentStatus> = new Set(['UNKNOWN', 'MANUAL_REVIEW'])

/** 운영자가 직접 결론지을 수 있는 상태. 그 외에는 서버가 거절한다. */
const REPAIRABLE: ReadonlySet<PaymentStatus> = new Set(['MANUAL_REVIEW'])

export type Tone = 'ok' | 'warn' | 'danger' | 'progress'

export function isTerminal(status: PaymentStatus): boolean {
  return TERMINAL.has(status)
}

export function needsAttention(status: PaymentStatus): boolean {
  return NEEDS_ATTENTION.has(status)
}

export function isRepairable(status: PaymentStatus): boolean {
  return REPAIRABLE.has(status)
}

export function toneOf(status: PaymentStatus): Tone {
  switch (status) {
    case 'SETTLED':
    case 'CLEARED':
      return 'ok'
    case 'UNKNOWN':
    case 'MANUAL_REVIEW':
      return 'danger'
    case 'REJECTED':
    case 'FAILED':
      return 'warn'
    default:
      return 'progress'
  }
}

/**
 * 상태를 한 줄로 설명한다.
 *
 * UNKNOWN 을 "실패"라고 쓰지 않는 것이 중요하다 — 화면의 낱말이 운영자의 판단을 만든다.
 */
export function describe(status: PaymentStatus): string {
  switch (status) {
    case 'RECEIVED':
      return '접수됨 — 검증 대기'
    case 'VALIDATED':
      return '검증 통과 — 청산망 송신 대기'
    case 'SENT_TO_CLEARING':
      return '청산망 송신 완료 — 응답 대기'
    case 'CLEARED':
      return '청산 완료 — 원장 반영 대기'
    case 'SETTLED':
      return '원장 반영까지 완료'
    case 'REJECTED':
      return '접수 단계에서 거절 — 돈이 나가지 않음'
    case 'FAILED':
      return '청산망에서 미처리 확정 — 돈이 나가지 않음'
    case 'UNKNOWN':
      return '응답 없음 — 지급 여부를 모름 (실패 아님)'
    case 'MANUAL_REVIEW':
      return '자동 확인 실패 — 운영자 확인 필요'
  }
}
