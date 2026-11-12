import { useEffect, useState } from 'react';
import { BrowserRouter, Link, Navigate, Route, Routes, useParams } from 'react-router';
import { AuthPage, AuthProvider, InvitationPage, OrganizationChooser, Protected, useAuth } from './auth';
import { Brand } from './components';
import { Icon } from './icons';
import { CatalogPage, ItemDetail } from './catalog';
import { LocationsPage } from './locations';
import { ActivityPage, PeoplePage } from './people';
import { OrganizationSettings, WorkspaceLayout } from './workspace';
import { ScanPage } from './scan';
import { PrintLabelsPage } from './labels';
import { CodeLinkPage } from './codes';
import { RequestDetailPage, RequestsPage } from './requests';

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
  return <div className="public-shell">
    <header className="public-header"><Brand /><div className="header-actions">{auth.user ? <Link className="button secondary" to="/app">Your businesses</Link> : <Link className="button ghost" to="/signin">Sign in</Link>}</div></header>
    <main className="landing-main" id="main">
      <h1>Know what you stock and where it’s kept.</h1>
      <p className="landing-lead">Backroom gives small teams one catalog of items, a map of their rooms, shelves and bins, and the right access for each person.</p>
      <div className="landing-actions">{auth.user
        ? <Link className="button primary" to="/app">Open your workspace</Link>
        : <><Link className="button primary" to="/register">Create an account</Link><Link className="button secondary" to="/signin">Sign in</Link></>}</div>
      <ul className="feature-list">
        <li><Icon name="catalog" /><div><strong>Catalog</strong><span>Items with SKUs and the unit you count them in.</span></div></li>
        <li><Icon name="location" /><div><strong>Locations</strong><span>Rooms, shelves, cabinets and bins, and the items expected in each.</span></div></li>
        <li><Icon name="people" /><div><strong>People</strong><span>Owners, managers and employees, each with the access they need.</span></div></li>
      </ul>
    </main>
    <footer className="public-footer"><span>Backroom</span><span className="status-line" role="status"><span className={'status-dot ' + (connected ? 'connected' : connected === false ? 'unavailable' : '')} aria-hidden="true" />{connected ? 'Service available' : connected === false ? 'Service unavailable — try again shortly' : 'Checking service…'}</span></footer>
  </div>;
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
    <Route path="/s/:token" element={<CodeLinkPage />} />
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
      <Route path="scan" element={<ScanPage />} />
      <Route path="labels" element={<PrintLabelsPage />} />
      <Route path="requests" element={<RequestsPage />} />
      <Route path="requests/:requestId" element={<RequestDetailPage />} />
    </Route>
    <Route path="*" element={<div className="auth-shell"><Brand /><section className="auth-card"><h1>Page not found</h1><p className="auth-lead">The link may be out of date.</p><Link className="button secondary full-width" to="/app">Go to your businesses</Link></section></div>} />
  </Routes></AuthProvider></BrowserRouter>;
}
