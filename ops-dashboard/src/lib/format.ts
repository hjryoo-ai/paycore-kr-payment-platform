/** 표시 형식. 금액과 시간은 잘못 보이면 잘못 판단하게 되므로 규칙을 한 곳에 둔다. */

/**
 * 원화 금액.
 *
 * 소수점을 절대 만들지 않는다. 서버는 KRW 정수(long)로 다루고, 화면에서 소수를 보여 주면
 * 운영자가 "1,500,000.00 원"을 보고 시스템이 소수를 다룬다고 오해하게 된다.
 */
export function krw(amount: number): string {
  return `${Math.trunc(amount).toLocaleString('ko-KR')}원`
}

/** 절대 시각. 대사·감사에서 기준 시간대가 흔들리면 안 되므로 항상 Asia/Seoul 로 고정한다. */
export function at(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
}

/**
 * 얼마나 오래 이 상태에 머물렀는가.
 *
 * UNKNOWN 워크리스트에서 가장 중요한 정보다 — 절대 시각만 보여 주면 운영자가 매번 뺄셈을 한다.
 */
export function since(iso: string, now: Date = new Date()): string {
  const seconds = Math.max(0, Math.floor((now.getTime() - new Date(iso).getTime()) / 1000))
  if (seconds < 60) return `${seconds}초`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}분`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}시간 ${minutes % 60}분`
  return `${Math.floor(hours / 24)}일 ${hours % 24}시간`
}

/** 오늘 업무일자(Asia/Seoul). 대사 화면의 기본 날짜다. */
export function businessDateToday(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now)
  return parts
}
