import { useEffect, useState } from 'react';
import { BrowserRouter, Link, Navigate, Route, Routes, useParams } from 'react-router';
import { AuthPage, AuthProvider, InvitationPage, OrganizationChooser, Protected, useAuth } from './auth';
import { Brand } from './components';
import { CatalogPage, ItemDetail } from './catalog';
import { LocationsPage } from './locations';
import { ActivityPage, PeoplePage } from './people';
import { OrganizationSettings, WorkspaceLayout } from './workspace';

function Landing() {
  const auth = useAuth();
  const [connected, setConnected] = useState<boolean | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    void fetch('/actuator/health', { signal: controller.signal }).then(async response => {
      const data = await response.json() as { status?: string };
      if (!controller.signal.aborted) setConnected(response.ok && data.status === 'UP');
    }).catch(() => { if (!controller.signal.aborted) setConnected(false); });
    return () => controller.abort();
  }, []);
  return <div className="app"><header className="header"><Brand /><Link className="button secondary" to={auth.user ? '/app' : '/signin'}>{auth.user ? 'Your businesses' : 'Sign in'}</Link></header><main className="main"><div className="eyebrow"><span /> A place for the work behind the work</div><h1>Everything behind<br />the business.</h1><p className="intro">Your stock. Your spaces. Your team.<br />A clearer picture of what keeps things moving.</p><div className="landing-actions"><Link className="button primary" to={auth.user ? '/app' : '/register'}>{auth.user ? 'Open your workspace' : 'Get started'}</Link><span className="muted">Made for small teams.</span></div><section className="connection-card"><span className="card-label">Connection status</span><div className="status-content" role="status"><span className={'status-indicator ' + (connected ? 'connected' : connected === false ? 'unavailable' : 'checking')} /><div><h2>{connected ? 'Connected' : connected === false ? 'Unable to connect' : 'Connecting…'}</h2><p>{connected ? 'Backroom is up and running.' : 'Check back in a moment.'}</p></div></div></section></main><footer className="footer"><span>Made for the everyday.</span><span>Backroom</span></footer></div>;
}
function LocationRoute() {
  const { locationId } = useParams();
  return <LocationsPage key={locationId ?? 'roots'} />;
}
function ItemRoute() {
  const { itemId } = useParams();
  return <ItemDetail key={itemId} />;
}
export function App() {
  return <BrowserRouter><AuthProvider><Routes>
    <Route path="/" element={<Landing />} />
    <Route path="/signin" element={<AuthPage mode="signin" />} />
    <Route path="/register" element={<AuthPage mode="register" />} />
    <Route path="/invite" element={<InvitationPage />} />
    <Route path="/app" element={<Protected><OrganizationChooser /></Protected>} />
    <Route path="/app/organizations/:orgId" element={<Protected><WorkspaceLayout /></Protected>}>
      <Route index element={<Navigate to="items" replace />} />
      <Route path="items" element={<CatalogPage />} />
      <Route path="items/:itemId" element={<ItemRoute />} />
      <Route path="locations" element={<LocationRoute />} />
      <Route path="locations/:locationId" element={<LocationRoute />} />
      <Route path="people" element={<PeoplePage />} />
      <Route path="activity" element={<ActivityPage />} />
      <Route path="settings" element={<OrganizationSettings />} />
    </Route>
    <Route path="*" element={<div className="public-shell"><Brand /><h1>Page not found</h1><Link to="/app">Your businesses</Link></div>} />
  </Routes></AuthProvider></BrowserRouter>;
}
