import { useCallback, useEffect, useState } from 'react'
import { request } from '../api/client'
import type { Page, PaymentDetail, PaymentStatus, PaymentSummary } from '../api/types'
import { ErrorBox } from '../components/ErrorBox'
import { StatusBadge } from '../components/StatusBadge'
import { Timeline } from '../components/Timeline'
import { at, krw, since } from '../lib/format'
import { describe } from '../lib/status'

const STATUSES: PaymentStatus[] = [
  'RECEIVED',
  'VALIDATED',
  'SENT_TO_CLEARING',
  'CLEARED',
  'SETTLED',
  'UNKNOWN',
  'MANUAL_REVIEW',
  'FAILED',
  'REJECTED',
]

/** ① 결제 검색 + 상태 타임라인 (docs §5.7). */
export function PaymentsScreen() {
  const [status, setStatus] = useState<PaymentStatus | ''>('')
  const [page, setPage] = useState(0)
  const [result, setResult] = useState<Page<PaymentSummary> | null>(null)
  const [selected, setSelected] = useState<PaymentDetail | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [loading, setLoading] = useState(false)

  const search = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const query = new URLSearchParams({ page: String(page), size: '20' })
      if (status) query.set('status', status)
      setResult(await request<Page<PaymentSummary>>(`/api/v1/payments?${query}`))
    } catch (e) {
      setError(e)
    } finally {
      setLoading(false)
    }
  }, [status, page])

  useEffect(() => {
    void search()
  }, [search])

  const open = async (paymentId: string) => {
    setError(null)
    try {
      setSelected(await request<PaymentDetail>(`/api/v1/payments/${paymentId}`))
    } catch (e) {
      setError(e)
    }
  }

  return (
    <section>
      <h2>결제 조회</h2>
      <p className="muted">
        기본은 최근 7일. 계좌번호는 서버에서 이미 마스킹되어 오며, 이 화면은 원본을 받지 않는다.
      </p>

      <div className="toolbar">
        <label>
          상태
          <select
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as PaymentStatus | '')
              setPage(0)
            }}
          >
            <option value="">전체</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        <button onClick={() => void search()} disabled={loading}>
          {loading ? '조회 중…' : '새로고침'}
        </button>
      </div>

      <ErrorBox error={error} />

      <table>
        <thead>
          <tr>
            <th>paymentId</th>
            <th>상태</th>
            <th className="num">금액</th>
            <th>출금 → 입금</th>
            <th>접수</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {result?.content.map((p) => (
            <tr key={p.paymentId}>
              <td>
                <code>{p.paymentId}</code>
              </td>
              <td>
                <StatusBadge status={p.status} />
              </td>
              <td className="num">{krw(p.amount)}</td>
              <td className="muted">
                {p.debtorAccount} → {p.creditorAccount} ({p.creditorBankCode})
              </td>
              <td className="muted">{at(p.createdAt)}</td>
              <td>
                <button onClick={() => void open(p.paymentId)}>타임라인</button>
              </td>
            </tr>
          ))}
          {result && result.content.length === 0 ? (
            <tr>
              <td colSpan={6} className="muted">
                조건에 맞는 결제가 없습니다.
              </td>
            </tr>
          ) : null}
        </tbody>
      </table>

      {result ? (
        <div className="pager">
          <button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            이전
          </button>
          <span className="muted">
            {result.page + 1} / {Math.max(1, result.totalPages)} · 총 {result.totalElements}건
          </span>
          <button disabled={result.page + 1 >= result.totalPages} onClick={() => setPage((p) => p + 1)}>
            다음
          </button>
        </div>
      ) : null}

      {selected ? (
        <aside className="detail">
          <header>
            <h3>
              <code>{selected.paymentId}</code> <StatusBadge status={selected.status} />
            </h3>
            <button onClick={() => setSelected(null)}>닫기</button>
          </header>
          <p className="muted">{describe(selected.status)}</p>
          <dl>
            <dt>endToEndId</dt>
            <dd>
              <code>{selected.endToEndId}</code>
              <span className="muted"> — 로그 검색 키 (docs §10.3)</span>
            </dd>
            <dt>금액</dt>
            <dd>{krw(selected.amount)}</dd>
            <dt>적요</dt>
            <dd>{selected.remittanceInfo ?? <span className="muted">없음</span>}</dd>
            <dt>마지막 변경</dt>
            <dd>
              {at(selected.updatedAt)} <span className="muted">({since(selected.updatedAt)} 전)</span>
            </dd>
          </dl>
          <h4>상태 타임라인</h4>
          <Timeline history={selected.history} />
        </aside>
      ) : null}
    </section>
  )
}
