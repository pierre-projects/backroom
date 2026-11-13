import { createContext, useContext, useEffect, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router';
import { clearWorkspaceNavigation } from './navigation';
import { ApiError, api, refreshCsrf, send } from './api';
import { Brand, ErrorMessage, Field, Loading, PageHeader, useResource, Pager } from './components';
import { Icon } from './icons';
import type { InvitationPreview, Organization, Page, User } from './types';
import { roleNames } from './types';

interface AuthState {
  user: User | null; loading: boolean; error?: unknown;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  reload: () => void;
}
const AuthContext = createContext<AuthState | null>(null);
export function useAuth() { return useContext(AuthContext)!; }

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>();
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(undefined);
    void refreshCsrf().then(() => api<User>('/auth/me', { signal: controller.signal })).then(value => {
      if (!controller.signal.aborted) setUser(value);
    }).catch(value => {
      if (!controller.signal.aborted) {
        setUser(null);
        if (!(value instanceof ApiError && value.status === 401)) setError(value);
      }
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [revision]);
  useEffect(() => {
    const expired = () => { clearWorkspaceNavigation(); setUser(null); setError(undefined); };
    window.addEventListener('backroom:session-expired', expired);
    return () => window.removeEventListener('backroom:session-expired', expired);
  }, []);
  async function login(email: string, password: string) {
    await refreshCsrf();
    const value = await api<User>('/auth/login', { method: 'POST', body: new URLSearchParams({ email, password }) });
    await refreshCsrf();
    setError(undefined); setUser(value);
  }
  async function logout() {
    await send('/auth/logout', 'POST');
    clearWorkspaceNavigation();
    setUser(null);
    await refreshCsrf();
  }
  return <AuthContext.Provider value={{ user, loading, error, login, logout, reload: () => setRevision(v => v + 1) }}>{children}</AuthContext.Provider>;
}
/** Only same-site paths may be used as a post-sign-in destination. */
export function safeNext(value: string | null): string | null {
  return value && value.startsWith('/') && !value.startsWith('//') && !value.startsWith('/\\') ? value : null;
}
export function Protected({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const location = useLocation();
  if (auth.loading) return <div className="auth-shell"><Loading /></div>;
  if (auth.error) return <div className="auth-shell"><Brand /><div className="auth-card"><ErrorMessage error={auth.error} retry={auth.reload} /></div></div>;
  if (!auth.user) return <Navigate to={'/signin?next=' + encodeURIComponent(location.pathname + location.search)} replace />;
  return children;
}
export function AuthForm({ initialMode = 'signin', invitation = false }: { initialMode?: 'signin' | 'register'; invitation?: boolean }) {
  const auth = useAuth();
  const [mode, setMode] = useState(initialMode);
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const email = String(data.get('email'));
    const password = String(data.get('password'));
    setPending(true); setError(undefined);
    try {
      await refreshCsrf();
      if (mode === 'register') await send('/auth/register', 'POST', { email, password, displayName: String(data.get('displayName')) });
      await auth.login(email, password);
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  const register = mode === 'register';
  const [params] = useSearchParams();
  const next = safeNext(params.get('next'));
  const keep = next ? '?next=' + encodeURIComponent(next) : '';
  return <section className="auth-card">
    <h1>{register ? 'Create your account' : 'Sign in to Backroom'}</h1>
    <p className="auth-lead">{invitation ? 'Sign in or create an account to see your invitation.' : register ? 'You can create a business or join one after this step.' : 'Use the email and password for your account.'}</p>
    <form onSubmit={submit}>
      <ErrorMessage error={error} />
      {register && <Field label="Your name"><input name="displayName" autoComplete="name" maxLength={100} required /></Field>}
      <Field label="Email"><input name="email" type="email" autoComplete="username" inputMode="email" maxLength={254} required /></Field>
      <Field label="Password"><input name="password" type="password" autoComplete={register ? 'new-password' : 'current-password'} minLength={12} required />{register && <small>At least 12 characters.</small>}</Field>
      <button className="button primary full-width" disabled={pending}>{pending ? (register ? 'Creating account…' : 'Signing in…') : register ? 'Create account' : 'Sign in'}</button>
    </form>
    {!register && <details className="forgot">
      <summary>Forgot your password?</summary>
      <p>Ask an Owner of your business for a password reset link. They can make one from <strong>People</strong>, next to your name. The link works once, for 24 hours.</p>
      <p>If you are the only Owner, add a second Owner you trust while you can still sign in; Backroom does not send email yet.</p>
    </details>}
    <p className="auth-switch">{register ? 'Already have an account?' : 'New to Backroom?'}{' '}
      {invitation ? <button className="text-button" type="button" onClick={() => { setMode(register ? 'signin' : 'register'); setError(undefined); }}>{register ? 'Sign in' : 'Create an account'}</button> :
        <Link to={(register ? '/signin' : '/register') + keep}>{register ? 'Sign in' : 'Create an account'}</Link>}
    </p>
  </section>;
}
export function AuthPage({ mode }: { mode: 'signin' | 'register' }) {
  const auth = useAuth();
  const [params] = useSearchParams();
  if (auth.loading) return <div className="auth-shell"><Loading /></div>;
  if (auth.user) return <Navigate to={safeNext(params.get('next')) ?? '/app'} replace />;
  return <div className="auth-shell"><Brand /><AuthForm key={mode} initialMode={mode} /></div>;
}
export function InvitationPage() {
  const auth = useAuth();
  const navigate = useNavigate();
  const token = new URLSearchParams(window.location.hash.slice(1)).get('token');
  const [preview, setPreview] = useState<InvitationPreview>();
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  useEffect(() => {
    if (!auth.user || !token) return;
    const controller = new AbortController();
    void api<InvitationPreview>('/invitations/preview', { method: 'POST', body: JSON.stringify({ token }), signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setPreview(value); })
      .catch(value => { if (!controller.signal.aborted) setError(value); });
    return () => controller.abort();
  }, [auth.user, token]);
  async function accept() {
    setPending(true); setError(undefined);
    try {
      const org = await send<Organization>('/invitations/accept', 'POST', { token });
      navigate('/app/organizations/' + org.id + '/home', { replace: true });
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  return <div className="auth-shell"><Brand />{auth.loading ? <Loading /> : !token
    ? <section className="auth-card"><h1>Invitation link incomplete</h1><p className="auth-lead">Open the full link your business Owner sent you, or ask them for a new one.</p><Link className="button secondary full-width" to="/app">Go to your businesses</Link></section>
    : !auth.user ? <AuthForm invitation />
    : <section className="auth-card"><h1>Join a business</h1><ErrorMessage error={error} />{!preview && !error && <Loading />}
      {preview && <><div className="invite-summary"><h2>{preview.organizationName}</h2><p>Role: <strong>{roleNames[preview.role]}</strong></p><p className="muted">Invitation expires {new Date(preview.expiresAt).toLocaleDateString(undefined, { dateStyle: 'medium' })}</p></div>
        <button className="button primary full-width" disabled={pending} onClick={() => void accept()}>{pending ? 'Joining…' : 'Accept invitation'}</button></>}
      <p className="auth-switch"><Link to="/app">Not now — go to your businesses</Link></p></section>}</div>;
}
interface ResetPreview { displayName: string; email: string; expiresAt: string }
/** Opened from an Owner's one-time link: set a new password, then sign straight in. */
export function ResetPasswordPage() {
  const auth = useAuth();
  const navigate = useNavigate();
  const [token] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get('token'));
  const [preview, setPreview] = useState<ResetPreview>();
  const [error, setError] = useState<unknown>();
  const [mismatch, setMismatch] = useState(false);
  const [pending, setPending] = useState(false);
  // Wait for the session check: it fetches the CSRF token, and a parallel fetch in a fresh browser would start a second session.
  useEffect(() => {
    if (!token || auth.loading) return;
    const controller = new AbortController();
    void api<ResetPreview>('/auth/password-reset/preview', { method: 'POST', body: JSON.stringify({ token }), signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setPreview(value); })
      .catch(value => { if (!controller.signal.aborted) setError(value); });
    return () => controller.abort();
  }, [token, auth.loading]);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const password = String(data.get('password'));
    if (password !== String(data.get('confirm'))) { setMismatch(true); return; }
    setMismatch(false); setPending(true); setError(undefined);
    try {
      if (auth.user) await auth.logout();
      const done = await send<{ email: string }>('/auth/password-reset', 'POST', { token, newPassword: password });
      history.replaceState(null, '', '/reset');
      await auth.login(done.email, password);
      navigate('/app', { replace: true });
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  return <div className="auth-shell"><Brand />{!token
    ? <section className="auth-card"><h1>Reset link incomplete</h1><p className="auth-lead">Open the full link an Owner gave you, or ask them for a new one.</p><Link className="button secondary full-width" to="/signin">Go to sign in</Link></section>
    : <section className="auth-card"><h1>Choose a new password</h1>
      {!preview && !error && <Loading />}
      {!preview && error ? <><ErrorMessage error={error} /><Link className="button secondary full-width" to="/signin">Go to sign in</Link></> : null}
      {preview && <>
        <p className="auth-lead">For <strong>{preview.displayName}</strong> ({preview.email}). Saving signs you out everywhere else.</p>
        <form onSubmit={submit}>
          <ErrorMessage error={error} />
          <Field label="New password"><input name="password" type="password" autoComplete="new-password" minLength={12} required /><small>At least 12 characters.</small></Field>
          <Field label="Confirm new password" error={mismatch ? 'The passwords don’t match.' : undefined}><input name="confirm" type="password" autoComplete="new-password" minLength={12} required /></Field>
          <button className="button primary full-width" disabled={pending}>{pending ? 'Saving…' : 'Set password and sign in'}</button>
        </form>
        <p className="auth-switch muted">Link expires {new Date(preview.expiresAt).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</p>
      </>}
    </section>}</div>;
}
export function OrganizationChooser() {
  useEffect(clearWorkspaceNavigation, []);
  const auth = useAuth();
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const organizations = useResource<Page<Organization>>('/organizations?page=' + page);
  const [showCreate, setShowCreate] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>();
  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setPending(true); setError(undefined);
    const name = String(new FormData(event.currentTarget).get('name'));
    try {
      const org = await send<Organization>('/organizations', 'POST', { name });
      navigate('/app/organizations/' + org.id + '/home');
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  const none = organizations.data?.totalElements === 0;
  return <div className="public-shell">
    <header className="public-header"><Brand to="/app" /><div className="header-actions"><span className="muted chooser-user">{auth.user!.displayName}</span><button type="button" className="button ghost" onClick={() => void auth.logout().catch(setError)}><Icon name="logout" />Sign out</button></div></header>
    <main className="chooser-main" id="main">
      <PageHeader title="Your businesses" description={none ? 'Create a business to start your catalog, or open an invitation link from your team.' : 'Choose a business to open its workspace.'}>{organizations.data && !none && <button type="button" className="button secondary" aria-expanded={showCreate} onClick={() => setShowCreate(v => !v)}><Icon name="plus" />New organization</button>}</PageHeader>
      <ErrorMessage error={organizations.error ?? error} retry={organizations.error ? organizations.reload : undefined} />
      {(showCreate || none) && <form className="panel organization-form" onSubmit={create}><h2 className="section-heading">Create an organization</h2><p className="section-intro">You will be its Owner. You can rename it later in Settings.</p><Field label="Business name"><input name="name" maxLength={120} required /></Field><button className="button primary" disabled={pending}>{pending ? 'Creating…' : 'Create organization'}</button></form>}
      {!organizations.data && !organizations.error && <div className="panel table-panel"><Loading variant="list" label="Loading businesses" /></div>}
      {!!organizations.data?.items.length && <ul className="organization-list">{organizations.data.items.map(org => <li key={org.id}><Link className="organization-row" to={'/app/organizations/' + org.id + '/home'}><span className="business-avatar" aria-hidden="true">{org.name.trim()[0]?.toUpperCase()}</span><div><h2>{org.name}</h2><span className="muted">{roleNames[org.role]}</span></div><Icon name="chevronRight" /></Link></li>)}</ul>}
      {organizations.data && <Pager data={organizations.data} onPage={setPage} />}
    </main></div>;
}
