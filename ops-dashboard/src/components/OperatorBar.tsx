/**
 * 운영자 식별 (docs §7.5 — 감사 로그 필수).
 *
 * 백엔드는 X-Operator 없는 요청을 거절한다. 화면에서도 값이 없으면 조치 버튼을 잠근다 —
 * 서버 거절만 믿으면 운영자가 버튼을 누르고 나서야 이유를 알게 된다.
 */
export function OperatorBar({
  operator,
  onChange,
}: {
  operator: string
  onChange: (value: string) => void
}) {
  return (
    <label className="operator">
      운영자
      <input
        value={operator}
        onChange={(e) => onChange(e.target.value)}
        placeholder="예: kim.ops"
        aria-label="운영자 식별자"
      />
      {operator.trim() === '' ? (
        <span className="operator__hint">조치하려면 식별자가 필요합니다 — 모든 개입은 기록됩니다.</span>
      ) : null}
    </label>
  )
}
