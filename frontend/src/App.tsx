import { BrowserRouter, Link, Navigate, Route, Routes, useParams } from 'react-router';
import { AuthPage, AuthProvider, InvitationPage, OrganizationChooser, Protected, ResetPasswordPage } from './auth';
import { Brand } from './components';
import { CatalogPage, ItemDetail } from './catalog';
import { LocationsPage } from './locations';
import { ActivityPage, PeoplePage } from './people';
import { OrganizationSettings, WorkspaceLayout } from './workspace';
import { ScanPage } from './scan';
import { PrintLabelsPage } from './labels';
import { CodeLinkPage } from './codes';
import { RequestDetailPage, RequestsPage } from './requests';
import { HomePage } from './home';
import { SearchPage } from './search';
import { ExportsPage } from './exports';
import { ImportItemsPage } from './imports';
import { ContactPage, LandingPage, PrivacyPage, SecurityPage, TermsPage } from './site';

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
    <Route path="/" element={<LandingPage />} />
    <Route path="/terms" element={<TermsPage />} />
    <Route path="/privacy" element={<PrivacyPage />} />
    <Route path="/security" element={<SecurityPage />} />
    <Route path="/contact" element={<ContactPage />} />
    <Route path="/signin" element={<AuthPage mode="signin" />} />
    <Route path="/register" element={<AuthPage mode="register" />} />
    <Route path="/invite" element={<InvitationPage />} />
    <Route path="/reset" element={<ResetPasswordPage />} />
    <Route path="/s/:token" element={<CodeLinkPage />} />
    <Route path="/app" element={<Protected><OrganizationChooser /></Protected>} />
    <Route path="/app/organizations/:orgId" element={<Protected><WorkspaceLayout /></Protected>}>
      <Route index element={<Navigate to="home" replace />} />
      <Route path="home" element={<HomePage />} />
      <Route path="search" element={<SearchPage />} />
      <Route path="exports" element={<ExportsPage />} />
      <Route path="items" element={<CatalogPage />} />
      <Route path="items/import" element={<ImportItemsPage />} />
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
