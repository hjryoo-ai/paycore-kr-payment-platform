import { ApiError } from '../api/client'

/** 서버가 보낸 problem+json 의 code/detail 을 그대로 보여 준다 (docs §10.2). */
export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null
  if (error instanceof ApiError) {
    return (
      <div className="error" role="alert">
        {error.code ? <code className="error__code">{error.code}</code> : null}
        <span>{error.message}</span>
      </div>
    )
  }
  return (
    <div className="error" role="alert">
      <span>{error instanceof Error ? error.message : String(error)}</span>
    </div>
  )
}
