/** 백엔드 응답 형태. 서버의 record 정의와 1:1로 맞춘다 (payment-api / recon-batch). */

export type PaymentStatus =
  | 'RECEIVED'
  | 'VALIDATED'
  | 'REJECTED'
  | 'SENT_TO_CLEARING'
  | 'CLEARED'
  | 'FAILED'
  | 'UNKNOWN'
  | 'MANUAL_REVIEW'
  | 'SETTLED'

export interface TimelineEntry {
  from: PaymentStatus | null
  to: PaymentStatus
  triggeredBy: string
  reason: string | null
  at: string
}

export interface PaymentDetail {
  paymentId: string
  endToEndId: string
  status: PaymentStatus
  /** 서버에서 이미 마스킹되어 온다. 프런트가 원본을 볼 일은 없다. */
  debtorAccount: string
  creditorAccount: string
  creditorBankCode: string
  amount: number
  currency: string
  remittanceInfo: string | null
  createdAt: string
  updatedAt: string
  history: TimelineEntry[]
}

export interface PaymentSummary {
  paymentId: string
  endToEndId: string
  status: PaymentStatus
  debtorAccount: string
  creditorAccount: string
  creditorBankCode: string
  amount: number
  currency: string
  createdAt: string
  updatedAt: string
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface WorklistItem {
  paymentId: string
  endToEndId: string
  status: PaymentStatus
  amount: number
  debtorAccount: string
  updatedAt: string
}

export type DeadLetterStatus = 'NEW' | 'REPUBLISHED' | 'DISCARDED'

export interface DeadLetter {
  deadLetterId: string
  originalTopic: string
  eventType: string | null
  eventId: string | null
  exceptionType: string | null
  exceptionMessage: string | null
  status: DeadLetterStatus
  receivedAt: string
  resolvedAt: string | null
}

export interface AuditEntry {
  auditId: number
  actor: string
  action: string
  targetType: string
  targetId: string
  detail: string | null
  at: string
}

export type BreakType =
  | 'MISSING_AT_CLEARING'
  | 'MISSING_AT_US'
  | 'AMOUNT_MISMATCH'
  | 'LEDGER_MISMATCH'
  | 'STATUS_MISMATCH'

export interface ReconBreak {
  breakId: number
  paymentId: string | null
  breakType: BreakType
  detail: string | null
  status: 'OPEN' | 'RESOLVED'
}

export interface ReconSummary {
  reconDate: string
  ourCount: number
  clearingCount: number
  ledgerCount: number
  openBreaks: number
  breaksByType: Record<string, number>
  reportFile: string
  executedAt: string
}
