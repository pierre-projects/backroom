import { useEffect, useId, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate, useOutletContext, useParams } from 'react-router';
import { useAuth } from './auth';
import { send } from './api';
import { Badge, Brand, ErrorMessage, Field, Loading, PageHeader, SuccessMessage, useResource } from './components';
import { Icon } from './icons';
import type { IconName } from './icons';
import type { Organization, Role } from './types';
import { roleNames } from './types';

interface Workspace { organization: Organization; base: string; canEdit: boolean; refresh: () => void }
export function useWorkspace() { return useOutletContext<Workspace>(); }

/** One source of truth for destinations: drives the desktop sidebar and the phone tab bar. */
const destinations: { path: string; label: string; icon: IconName; roles: Role[] }[] = [
  { path: 'items', label: 'Inventory', icon: 'catalog', roles: ['OWNER', 'MANAGER', 'EMPLOYEE'] },
  { path: 'locations', label: 'Locations', icon: 'location', roles: ['OWNER', 'MANAGER', 'EMPLOYEE'] },
  { path: 'activity', label: 'Activity', icon: 'activity', roles: ['OWNER', 'MANAGER'] },
  { path: 'people', label: 'People', icon: 'people', roles: ['OWNER'] },
  { path: 'settings', label: 'Settings', icon: 'settings', roles: ['OWNER'] },
];

function initials(name: string) {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map(part => part[0]!.toUpperCase()).join('') || '?';
}

function AccountMenu({ onSignOut }: { onSignOut: () => void }) {
  const auth = useAuth();
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  const wrapper = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const key = (event: KeyboardEvent) => { if (event.key === 'Escape') { setOpen(false); trigger.current?.focus(); } };
    const pointer = (event: PointerEvent) => { if (!wrapper.current?.contains(event.target as Node)) setOpen(false); };
    document.addEventListener('keydown', key);
    document.addEventListener('pointerdown', pointer);
    return () => { document.removeEventListener('keydown', key); document.removeEventListener('pointerdown', pointer); };
  }, [open]);
  const name = auth.user?.displayName ?? '';
  return <div className="account" ref={wrapper}>
    <button ref={trigger} type="button" className="avatar-button" aria-expanded={open} aria-controls={menuId} aria-label={'Account: ' + name} onClick={() => setOpen(value => !value)}><span className="avatar" aria-hidden="true">{initials(name)}</span></button>
    <div className="menu account-menu" id={menuId} hidden={!open}>
      <div className="menu-label"><strong>{name}</strong>{auth.user?.email}</div>
      <Link className="menu-item" to="/app" onClick={() => setOpen(false)}><Icon name="switch" />Switch business</Link>
      <button type="button" className="menu-item" onClick={() => { setOpen(false); onSignOut(); }}><Icon name="logout" />Sign out</button>
    </div>
  </div>;
}

export function WorkspaceLayout() {
  const { orgId } = useParams();
  const organization = useResource<Organization>('/organizations/' + orgId);
  const auth = useAuth();
  const location = useLocation();
  const [error, setError] = useState<unknown>();
  useEffect(() => { window.scrollTo(0, 0); }, [location.pathname]);
  if (organization.error) return <div className="public-shell"><Brand /><div className="public-body"><ErrorMessage error={organization.error} retry={organization.reload} /><Link to="/app">Back to your businesses</Link></div></div>;
  if (!organization.data) return <div className="public-shell"><Loading /></div>;
  const org = organization.data;
  const root = '/app/organizations/' + org.id;
  const workspace = { organization: org, base: '/organizations/' + org.id, canEdit: org.role !== 'EMPLOYEE', refresh: organization.reload };
  const signOut = () => void auth.logout().catch(setError);
  return <div className="workspace">
    <a className="skip-link" href="#main">Skip to content</a>
    <header className="topbar">
      <Brand compact to="/app" />
      <Link className="topbar-business" to="/app"><span className="topbar-business-name">{org.name}</span><span className="sr-only">, {roleNames[org.role]}. Switch business</span><Icon name="chevronDown" className="small" /></Link>
      <AccountMenu onSignOut={signOut} />
    </header>
    <aside className="sidebar">
      <div className="sidebar-brand"><Brand to="/app" /></div>
      <div className="business-switcher">
        <span className="business-avatar" aria-hidden="true">{initials(org.name)}</span>
        <div className="business-meta"><strong>{org.name}</strong><span>{roleNames[org.role]}</span></div>
        <Link className="switch-link" to="/app"><Icon name="switch" className="small" />Switch business</Link>
      </div>
      <nav className="primary-nav" aria-label="Business navigation">
        {destinations.filter(destination => destination.roles.includes(org.role)).map(destination =>
          <NavLink key={destination.path} className="nav-link" to={root + '/' + destination.path}><Icon name={destination.icon} /><span>{destination.label}</span></NavLink>)}
      </nav>
      <div className="sidebar-account">
        <span className="avatar" aria-hidden="true">{initials(auth.user?.displayName ?? '')}</span>
        <div className="sidebar-account-name"><strong>{auth.user?.displayName}</strong><span>{auth.user?.email}</span></div>
        <button type="button" className="icon-button" aria-label="Sign out" title="Sign out" onClick={signOut}><Icon name="logout" /></button>
      </div>
    </aside>
    <main className="workspace-main" id="main" tabIndex={-1}><div className="workspace-content"><ErrorMessage error={error} /><Outlet key={org.id} context={workspace} /></div></main>
  </div>;
}

export function OrganizationSettings() {
  const { organization, base, refresh } = useWorkspace();
  const navigate = useNavigate();
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  const [saved, setSaved] = useState(false);
  if (organization.role !== 'OWNER') return <ErrorMessage error={new Error('Only an Owner can change organization settings.')} />;
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setPending(true); setError(undefined); setSaved(false);
    try {
      await send(base, 'PATCH', { name: String(new FormData(event.currentTarget).get('name')), expectedVersion: organization.version });
      refresh(); setSaved(true); navigate('/app/organizations/' + organization.id + '/settings');
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  return <><PageHeader title="Settings" description="Details shown to everyone in this business." />
    <form className="panel narrow" onSubmit={save}>
      <h2 className="section-heading">Business details</h2>
      <p className="section-intro">Your role: <Badge>{roleNames[organization.role]}</Badge></p>
      <ErrorMessage error={error} retry={refresh} />
      {saved && <SuccessMessage>Business name saved.</SuccessMessage>}
      <Field label="Business name"><input name="name" defaultValue={organization.name} maxLength={120} required /></Field>
      <button className="button primary" disabled={pending}>{pending ? 'Saving…' : 'Save changes'}</button>
    </form></>;
}
