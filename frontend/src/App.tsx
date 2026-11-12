import { useEffect, useState } from 'react';

type Connection = 'checking' | 'connected' | 'unavailable';

export function App() {
  const [connection, setConnection] = useState<Connection>('checking');
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    const timeout = window.setTimeout(() => controller.abort(), 10_000);
    setConnection('checking');

    async function checkConnection() {
      try {
        const response = await fetch('/actuator/health', {
          signal: controller.signal,
          headers: { Accept: 'application/json' },
          cache: 'no-store',
        });
        const health: unknown = await response.json();
        if (!response.ok || typeof health !== 'object' || health === null
          || !('status' in health) || health.status !== 'UP') {
          throw new Error('Service unavailable');
        }
        if (active) setConnection('connected');
      } catch {
        if (active) setConnection('unavailable');
      } finally {
        window.clearTimeout(timeout);
      }
    }

    void checkConnection();
    return () => {
      active = false;
      controller.abort();
      window.clearTimeout(timeout);
    };
  }, [attempt]);

  const status = {
    checking: { title: 'Connecting…', detail: 'Checking that Backroom is ready.' },
    connected: { title: 'Connected', detail: 'Backroom is up and running.' },
    unavailable: { title: 'Unable to connect', detail: 'Backroom is taking a moment. Try again shortly.' },
  }[connection];

  return (
    <div className="app">
      <header className="header">
        <a className="brand" href="/" aria-label="Backroom home">
          <svg className="brand-mark" viewBox="0 0 32 32" fill="none" aria-hidden="true">
            <path d="M5 5h22v22H5zM5 13h22M13 13v14" stroke="currentColor" strokeWidth="2.5" />
          </svg>
          Backroom<span className="brand-period">.</span>
        </a>
        <span className="phase-label">Getting started</span>
      </header>
      <main className="main">
        <div className="eyebrow"><span /> A place for the work behind the work</div>
        <h1>Everything behind<br />the business.</h1>
        <p className="intro">
          Your stock. Your spaces. Your team.<br className="desktop-break" />{' '}
          A clearer picture of what keeps things moving.
        </p>
        <section className="connection-card" aria-labelledby="connection-heading">
          <div className="card-top">
            <span className="card-label" id="connection-heading">Connection status</span>
            <span className="card-index" aria-hidden="true">01 / SETUP</span>
          </div>
          <div className="status-content" role="status" aria-live="polite" aria-atomic="true">
            <span className={`status-indicator ${connection}`} aria-hidden="true" />
            <div>
              <h2>{status.title}</h2>
              <p>{status.detail}</p>
            </div>
          </div>
          {connection === 'unavailable' && (
            <button type="button" onClick={() => setAttempt(value => value + 1)}>
              Try again <span aria-hidden="true">↗</span>
            </button>
          )}
        </section>
        <p className="setup-note">The foundation is ready. Inventory and team tools are on the way.</p>
      </main>
      <footer className="footer">
        <span>Made for the everyday.</span>
        <span>Backroom · Phase 0</span>
      </footer>
    </div>
  );
}
