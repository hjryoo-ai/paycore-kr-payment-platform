import { describe, expect, it } from 'vitest'
import { at, businessDateToday, krw, since } from './format'

describe('krw', () => {
  it('원화는 정수로만 보여 준다 — 소수점이 보이면 시스템이 소수를 다룬다고 오해하게 된다', () => {
    expect(krw(1500000)).toBe('1,500,000원')
    expect(krw(0)).toBe('0원')
    expect(krw(1)).toBe('1원')
  })

  it('혹시 소수가 흘러들어와도 화면에는 소수를 내지 않는다', () => {
    expect(krw(1500000.9)).toBe('1,500,000원')
  })
})

describe('at', () => {
  it('항상 Asia/Seoul 기준으로 보여 준다 — 대사·감사에서 기준 시간대가 흔들리면 안 된다', () => {
    // 2026-08-20T09:00:00Z = 서울 18:00
    expect(at('2026-08-20T09:00:00Z')).toContain('18:00:00')
  })
})

describe('since', () => {
  const now = new Date('2026-08-20T12:00:00Z')

  it('초/분/시간/일 단위로 줄여 보여 준다', () => {
    expect(since('2026-08-20T11:59:30Z', now)).toBe('30초')
    expect(since('2026-08-20T11:30:00Z', now)).toBe('30분')
    expect(since('2026-08-20T09:30:00Z', now)).toBe('2시간 30분')
    expect(since('2026-08-18T09:00:00Z', now)).toBe('2일 3시간')
  })

  it('미래 시각이어도 음수를 보여 주지 않는다 — 시계 오차가 화면을 이상하게 만들면 안 된다', () => {
    expect(since('2026-08-20T12:00:30Z', now)).toBe('0초')
  })
})

describe('businessDateToday', () => {
  it('UTC 로 어제여도 서울 기준 오늘을 준다', () => {
    // 2026-08-20T16:00:00Z = 서울 2026-08-21 01:00
    expect(businessDateToday(new Date('2026-08-20T16:00:00Z'))).toBe('2026-08-21')
  })
})
