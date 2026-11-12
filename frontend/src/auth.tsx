import { createContext, useContext, useEffect, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { Link, Navigate, useNavigate } from 'react-router';
import { ApiError, api, refreshCsrf, send } from './api';
import { Brand, ErrorMessage, Field, Loading, PageHeader, useResource, Pager } from './components';
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
    const expired = () => { setUser(null); setError(undefined); };
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
    setUser(null);
    await refreshCsrf();
  }
  return <AuthContext.Provider value={{ user, loading, error, login, logout, reload: () => setRevision(v => v + 1) }}>{children}</AuthContext.Provider>;
}
export function Protected({ children }: { children: ReactNode }) {
  const auth = useAuth();
  if (auth.loading) return <div className="public-shell"><Loading /></div>;
  if (auth.error) return <div className="public-shell"><Brand /><ErrorMessage error={auth.error} retry={auth.reload} /></div>;
  if (!auth.user) return <Navigate to="/signin" replace />;
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
  return <section className="auth-card">
    <div className="eyebrow">{invitation ? 'Join your team' : 'Welcome to Backroom'}</div>
    <h1>{mode === 'register' ? 'Create your account' : 'Welcome back.'}</h1>
    <p className="muted">{invitation ? 'Sign in or create an account to review this invitation.' : 'A place for the work behind the work.'}</p>
    <form onSubmit={submit}>
      <ErrorMessage error={error} />
      {mode === 'register' && <Field label="Your name"><input name="displayName" autoComplete="name" maxLength={100} required /></Field>}
      <Field label="Email"><input name="email" type="email" autoComplete="username" maxLength={254} required /></Field>
      <Field label="Password"><input name="password" type="password" autoComplete={mode === 'register' ? 'new-password' : 'current-password'} minLength={12} required /></Field>
      {mode === 'register' && <p className="field-help">Use at least 12 characters. Passwords may contain up to 72 UTF-8 bytes.</p>}
      <button className="button primary full-width" disabled={pending}>{pending ? 'Please wait…' : mode === 'register' ? 'Create account' : 'Sign in'}</button>
    </form>
    <p className="auth-switch">{mode === 'register' ? 'Already have an account?' : 'New here?'}{' '}
      {invitation ? <button className="text-button" type="button" onClick={() => { setMode(mode === 'register' ? 'signin' : 'register'); setError(undefined); }}>{mode === 'register' ? 'Sign in' : 'Create an account'}</button> :
        <Link to={mode === 'register' ? '/signin' : '/register'}>{mode === 'register' ? 'Sign in' : 'Create an account'}</Link>}
    </p>
  </section>;
}
export function AuthPage({ mode }: { mode: 'signin' | 'register' }) {
  const auth = useAuth();
  if (auth.loading) return <Loading />;
  if (auth.user) return <Navigate to="/app" replace />;
  return <div className="public-shell"><Brand /><AuthForm key={mode} initialMode={mode} /></div>;
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
      navigate('/app/organizations/' + org.id + '/items', { replace: true });
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  return <div className="public-shell"><Brand />{auth.loading ? <Loading /> : !token ? <div className="auth-card"><h1>Invitation unavailable</h1><p>Open the complete link shared by your business Owner.</p><Link to="/app">Your businesses</Link></div> : !auth.user ? <AuthForm invitation /> :
    <section className="auth-card"><div className="eyebrow">Your team is waiting</div><h1>Join a business</h1><ErrorMessage error={error} />{!preview && !error && <Loading />}{preview && <><h2>{preview.organizationName}</h2><p>You're invited to join as <strong>{roleNames[preview.role]}</strong>.</p><p className="muted">This invitation expires {new Date(preview.expiresAt).toLocaleDateString()}.</p><button className="button primary" disabled={pending} onClick={() => void accept()}>{pending ? 'Joining…' : 'Accept invitation'}</button></>}<p><Link to="/app">Back to your businesses</Link></p></section>}</div>;
}
export function OrganizationChooser() {
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
      navigate('/app/organizations/' + org.id + '/items');
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  return <div className="chooser-shell"><header className="header"><Brand /><button className="text-button" onClick={() => void auth.logout().catch(setError)}>Sign out</button></header>
    <main><PageHeader eyebrow={'Hello, ' + auth.user!.displayName} title="Your businesses" description="Choose a workspace, or make a home for your team."><button className="button primary" onClick={() => setShowCreate(v => !v)}>New organization</button></PageHeader>
      <ErrorMessage error={organizations.error ?? error} retry={organizations.error ? organizations.reload : undefined} />
      {(showCreate || organizations.data?.totalElements === 0) && <form className="panel organization-form" onSubmit={create}><h2>Create an organization</h2><Field label="Business name"><input name="name" maxLength={120} required /></Field><button className="button primary" disabled={pending}>{pending ? 'Creating…' : 'Create organization'}</button></form>}
      {!organizations.data && !organizations.error && <Loading />}
      <div className="organization-grid">{organizations.data?.items.map(org => <Link className="organization-card" key={org.id} to={'/app/organizations/' + org.id + '/items'}><div className="organization-initial">{org.name[0].toUpperCase()}</div><h2>{org.name}</h2><span className="muted">{roleNames[org.role]}</span><span className="card-arrow" aria-hidden="true">↗</span></Link>)}</div>
      {organizations.data && <Pager data={organizations.data} onPage={setPage} />}
    </main></div>;
}
