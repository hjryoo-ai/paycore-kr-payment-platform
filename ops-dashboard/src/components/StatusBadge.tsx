import type { PaymentStatus } from '../api/types'
import { describe, toneOf } from '../lib/status'

export function StatusBadge({ status }: { status: PaymentStatus }) {
  return (
    <span className={`badge badge--${toneOf(status)}`} title={describe(status)}>
      {status}
    </span>
  )
}
