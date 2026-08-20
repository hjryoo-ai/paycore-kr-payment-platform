import { useCallback, useEffect, useState } from 'react'
import { request } from '../api/client'
import type { BreakType, ReconBreak, ReconSummary } from '../api/types'
import { ErrorBox } from '../components/ErrorBox'
import { businessDateToday } from '../lib/format'

/** 유형별로 무엇부터 봐야 하는지. 리포트의 "조사 순서 제안"과 같은 기준이다 (docs §5.6). */
const PRIORITY: Record<BreakType, { order: number; meaning: string }> = {
  MISSING_AT_CLEARING: { order: 1, meaning: '우리는 지급 완료로 아는데 청산망 파일에 없음 — 유령 지급일 수 있다' },
  STATUS_MISMATCH: { order: 2, meaning: '양쪽이 같은 이체의 결과를 다르게 말함' },
  MISSING_AT_US: { order: 3, meaning: '청산망에는 결론이 있는데 우리는 미확정 — 대개 방치된 UNKNOWN' },
  LEDGER_MISMATCH: { order: 4, meaning: '결제 상태와 원장이 어긋남 — 돈보다 기록의 문제일 가능성' },
  AMOUNT_MISMATCH: { order: 5, meaning: '양쪽이 아는 금액이 다름' },
}

/** ③ 대사 break 목록 (docs §5.7). */
export function ReconScreen() {
  const [date, setDate] = useState(businessDateToday())
  const [breaks, setBreaks] = useState<ReconBreak[]>([])
  const [summary, setSummary] = useState<ReconSummary | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    setError(null)
    try {
      setBreaks(await request<ReconBreak[]>(`/api/v1/recon/breaks?date=${date}&status=OPEN`))
    } catch (e) {
      setError(e)
    }
  }, [date])

  useEffect(() => {
    void load()
  }, [load])

  const run = async () => {
    setBusy(true)
    setError(null)
    try {
      setSummary(await request<ReconSummary>(`/api/v1/recon/run?date=${date}`, { method: 'POST' }))
      await load()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  const sorted = [...breaks].sort(
    (a, b) => (PRIORITY[a.breakType]?.order ?? 99) - (PRIORITY[b.breakType]?.order ?? 99),
  )

  return (
    <section>
      <h2>일마감 대사</h2>
      <p className="muted">
        PAYMENT(우리) · 청산망 EOD(상대) · LEDGER(회계) 세 주장을 맞춰 본다. 목록은 <strong>조사 순서</strong>대로
        정렬된다 — 돈이 실제로 움직인 쪽부터다.
      </p>

      <div className="toolbar">
        <label>
          업무일자
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
        </label>
        <button onClick={() => void run()} disabled={busy}>
          {busy ? '대사 중…' : '대사 실행'}
        </button>
        <button onClick={() => void load()}>새로고침</button>
      </div>

      <ErrorBox error={error} />

      {summary ? (
        <div className={summary.openBreaks === 0 ? 'summary summary--ok' : 'summary summary--danger'}>
          <strong>{summary.openBreaks === 0 ? '불일치 없음' : `불일치 ${summary.openBreaks}건`}</strong>
          <span className="muted">
            우리 {summary.ourCount} · 청산망 {summary.clearingCount} · 원장 {summary.ledgerCount}
          </span>
          <span className="muted">리포트: {summary.reportFile}</span>
        </div>
      ) : null}

      <table>
        <thead>
          <tr>
            <th>우선순위</th>
            <th>유형</th>
            <th>paymentId</th>
            <th>내용</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((b) => (
            <tr key={b.breakId}>
              <td className="num">{PRIORITY[b.breakType]?.order ?? '—'}</td>
              <td>
                <code>{b.breakType}</code>
                <div className="muted">{PRIORITY[b.breakType]?.meaning}</div>
              </td>
              <td>
                {b.paymentId ? (
                  <code>{b.paymentId}</code>
                ) : (
                  <span className="muted">우리 DB 에 없음</span>
                )}
              </td>
              <td>{b.detail}</td>
            </tr>
          ))}
          {sorted.length === 0 ? (
            <tr>
              <td colSpan={4} className="muted">
                미해결 불일치가 없습니다. 대사를 아직 돌리지 않았다면 위 버튼으로 실행하세요 —
                <strong> 불일치 0건과 &lsquo;마감이 안 돌았음&rsquo;은 다릅니다.</strong>
              </td>
            </tr>
          ) : null}
        </tbody>
      </table>
    </section>
  )
}
