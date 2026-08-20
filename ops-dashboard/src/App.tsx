import { useState } from 'react'
import { PaymentsScreen } from './screens/PaymentsScreen'
import { ReconScreen } from './screens/ReconScreen'
import { WorklistScreen } from './screens/WorklistScreen'

type Tab = 'payments' | 'worklist' | 'recon'

const TABS: { id: Tab; label: string }[] = [
  { id: 'payments', label: '결제 조회' },
  { id: 'worklist', label: '워크리스트' },
  { id: 'recon', label: '대사' },
]

/**
 * 화면은 3개뿐이다 (docs §5.7). 디자인보다 <b>운영자가 장애를 처리하는 흐름</b>을 보여 주는 것이 목적이다.
 *
 * 운영자 식별자는 앱 수준에서 들고 있는다 — 탭을 옮길 때마다 다시 입력하게 만들면
 * 사람이 브라우저에 저장해 두고 아무나 쓰게 된다.
 */
export function App() {
  const [tab, setTab] = useState<Tab>('worklist')
  const [operator, setOperator] = useState('')

  return (
    <div className="app">
      <header className="app__header">
        <h1>PayCore-KR 운영</h1>
        <nav>
          {TABS.map((t) => (
            <button
              key={t.id}
              className={tab === t.id ? 'tab tab--active' : 'tab'}
              onClick={() => setTab(t.id)}
              aria-current={tab === t.id ? 'page' : undefined}
            >
              {t.label}
            </button>
          ))}
        </nav>
      </header>

      <main>
        {tab === 'payments' ? <PaymentsScreen /> : null}
        {tab === 'worklist' ? (
          <WorklistScreen operator={operator} onOperatorChange={setOperator} />
        ) : null}
        {tab === 'recon' ? <ReconScreen /> : null}
      </main>
    </div>
  )
}
