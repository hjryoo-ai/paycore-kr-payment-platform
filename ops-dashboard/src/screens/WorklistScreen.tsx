import { useCallback, useEffect, useState } from 'react'
import { request } from '../api/client'
import type { AuditEntry, DeadLetter, PaymentStatus, WorklistItem } from '../api/types'
import { ErrorBox } from '../components/ErrorBox'
import { OperatorBar } from '../components/OperatorBar'
import { StatusBadge } from '../components/StatusBadge'
import { at, krw, since } from '../lib/format'
import { describe, isRepairable } from '../lib/status'

/**
 * ② UNKNOWN · MANUAL_REVIEW · DLQ 워크리스트 (docs §5.7).
 *
 * 이 화면의 목적은 목록을 보여 주는 것이 아니라 <b>조치를 끝내는 것</b>이다. 그래서
 * 근거 입력과 조치 버튼이 같은 자리에 있고, 조치 후에는 감사 기록이 바로 보인다.
 */
export function WorklistScreen({
  operator,
  onOperatorChange,
}: {
  operator: string
  onOperatorChange: (value: string) => void
}) {
  const [status, setStatus] = useState<PaymentStatus>('MANUAL_REVIEW')
  const [items, setItems] = useState<WorklistItem[]>([])
  const [deadLetters, setDeadLetters] = useState<DeadLetter[]>([])
  const [audit, setAudit] = useState<AuditEntry[]>([])
  const [reason, setReason] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    setError(null)
    try {
      const [worklist, dlt] = await Promise.all([
        request<WorklistItem[]>(`/api/v1/ops/worklist?status=${status}`),
        request<DeadLetter[]>('/api/v1/ops/dead-letters?status=NEW'),
      ])
      setItems(worklist)
      setDeadLetters(dlt)
    } catch (e) {
      setError(e)
    }
  }, [status])

  useEffect(() => {
    void load()
  }, [load])

  const canAct = operator.trim() !== '' && reason.trim() !== ''

  const act = async (run: () => Promise<unknown>, targetType: string, targetId: string) => {
    setBusy(true)
    setError(null)
    try {
      await run()
      setAudit(
        await request<AuditEntry[]>(
          `/api/v1/ops/audit?targetType=${targetType}&targetId=${encodeURIComponent(targetId)}`,
        ),
      )
      setReason('')
      await load()
    } catch (e) {
      setError(e)
    } finally {
      setBusy(false)
    }
  }

  const repair = (paymentId: string, decision: 'CLEARED' | 'FAILED') =>
    act(
      () =>
        request(`/api/v1/ops/payments/${paymentId}/repair`, {
          method: 'POST',
          operator,
          body: { decision, reason },
        }),
      'PAYMENT',
      paymentId,
    )

  const deadLetterAction = (deadLetterId: string, action: 'republish' | 'discard') =>
    act(
      () =>
        request(`/api/v1/ops/dead-letters/${deadLetterId}/${action}`, {
          method: 'POST',
          operator,
          body: { reason },
        }),
      'DEAD_LETTER',
      deadLetterId,
    )

  return (
    <section>
      <h2>워크리스트</h2>
      <p className="muted">
        자동 처리가 손을 뗀 지점이다. 모든 조치는 <strong>누가·언제·왜</strong>가 상태 변경과 같은 커밋에 기록된다.
      </p>

      <div className="toolbar">
        <OperatorBar operator={operator} onChange={onOperatorChange} />
        <label className="reason">
          조치 근거
          <input
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="예: 청산망 원장에서 지급 확인 (전화 확인, 담당 박OO)"
            aria-label="조치 근거"
          />
        </label>
      </div>

      <ErrorBox error={error} />

      <h3>결제</h3>
      <div className="toolbar">
        <label>
          상태
          <select value={status} onChange={(e) => setStatus(e.target.value as PaymentStatus)}>
            <option value="MANUAL_REVIEW">MANUAL_REVIEW — 운영자 확인 필요</option>
            <option value="UNKNOWN">UNKNOWN — 조회 진행 중</option>
          </select>
        </label>
        <button onClick={() => void load()}>새로고침</button>
      </div>

      <table>
        <thead>
          <tr>
            <th>paymentId</th>
            <th>상태</th>
            <th className="num">금액</th>
            <th>체류</th>
            <th>조치</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.paymentId}>
              <td>
                <code>{item.paymentId}</code>
                <div className="muted">{item.endToEndId}</div>
              </td>
              <td>
                <StatusBadge status={item.status} />
                <div className="muted">{describe(item.status)}</div>
              </td>
              <td className="num">{krw(item.amount)}</td>
              <td>{since(item.updatedAt)}</td>
              <td>
                {isRepairable(item.status) ? (
                  <div className="actions">
                    <button
                      disabled={!canAct || busy}
                      onClick={() => void repair(item.paymentId, 'CLEARED')}
                    >
                      지급 확인(CLEARED)
                    </button>
                    <button
                      disabled={!canAct || busy}
                      onClick={() => void repair(item.paymentId, 'FAILED')}
                    >
                      미처리 확인(FAILED)
                    </button>
                  </div>
                ) : (
                  <span className="muted">조회 진행 중 — 아직 사람이 손댈 단계가 아니다</span>
                )}
              </td>
            </tr>
          ))}
          {items.length === 0 ? (
            <tr>
              <td colSpan={5} className="muted">
                해당 상태의 결제가 없습니다.
              </td>
            </tr>
          ) : null}
        </tbody>
      </table>

      <h3>DLT (미처리 메시지)</h3>
      <p className="muted">
        자동 재주입은 하지 않는다. 원인을 확인한 뒤에만 재발행한다 — 소비자가 모두 inbox 중복 제거를
        거치므로 재발행 자체는 안전하다.
      </p>
      <table>
        <thead>
          <tr>
            <th>eventType</th>
            <th>원인</th>
            <th>수신</th>
            <th>조치</th>
          </tr>
        </thead>
        <tbody>
          {deadLetters.map((dl) => (
            <tr key={dl.deadLetterId}>
              <td>
                <code>{dl.eventType ?? '알 수 없음'}</code>
                <div className="muted">{dl.eventId ?? '—'}</div>
              </td>
              <td>
                <code className="muted">{dl.exceptionType ?? '—'}</code>
                <div className="muted">{dl.exceptionMessage ?? ''}</div>
              </td>
              <td className="muted">{at(dl.receivedAt)}</td>
              <td>
                <div className="actions">
                  <button
                    disabled={!canAct || busy}
                    onClick={() => void deadLetterAction(dl.deadLetterId, 'republish')}
                  >
                    재발행
                  </button>
                  <button
                    disabled={!canAct || busy}
                    onClick={() => void deadLetterAction(dl.deadLetterId, 'discard')}
                  >
                    폐기
                  </button>
                </div>
              </td>
            </tr>
          ))}
          {deadLetters.length === 0 ? (
            <tr>
              <td colSpan={4} className="muted">
                미처리 DLT 없음. 이게 정상 상태다.
              </td>
            </tr>
          ) : null}
        </tbody>
      </table>

      {audit.length > 0 ? (
        <>
          <h3>방금 조치한 건의 감사 기록</h3>
          <ul className="audit">
            {audit.map((a) => (
              <li key={a.auditId}>
                <time>{at(a.at)}</time> <strong>{a.actor}</strong> <code>{a.action}</code>{' '}
                <span className="muted">{a.detail}</span>
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </section>
  )
}
