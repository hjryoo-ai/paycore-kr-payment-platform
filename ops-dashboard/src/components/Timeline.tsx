import type { TimelineEntry } from '../api/types'
import { at } from '../lib/format'
import { StatusBadge } from './StatusBadge'

/**
 * 상태 타임라인 (PAYMENT_STATUS_HISTORY 기반, docs §5.7).
 *
 * "무엇이 이 전이를 일으켰는가"(triggeredBy)를 반드시 함께 보여 준다. 사고 조사에서
 * 실제로 필요한 것은 상태의 나열이 아니라 <b>누가/무엇이 바꿨는지</b>다.
 */
export function Timeline({ history }: { history: TimelineEntry[] }) {
  if (history.length === 0) {
    return <p className="muted">이력이 없습니다.</p>
  }
  return (
    <ol className="timeline">
      {history.map((entry, index) => (
        <li key={`${entry.at}-${index}`}>
          <time>{at(entry.at)}</time>
          <span className="timeline__transition">
            {entry.from ? <code>{entry.from}</code> : <code className="muted">신규</code>}
            <span aria-hidden="true"> → </span>
            <StatusBadge status={entry.to} />
          </span>
          <span className="timeline__by">{entry.triggeredBy}</span>
          {entry.reason ? <span className="timeline__reason">{entry.reason}</span> : null}
        </li>
      ))}
    </ol>
  )
}
