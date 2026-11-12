import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, NavLink, Outlet, useNavigate, useOutletContext, useParams } from 'react-router';
import { useAuth } from './auth';
import { send } from './api';
import { Badge, Brand, ErrorMessage, Field, Loading, PageHeader, useResource } from './components';
import type { Organization } from './types';
import { roleNames } from './types';

interface Workspace { organization: Organization; base: string; canEdit: boolean; refresh: () => void }
export function useWorkspace() { return useOutletContext<Workspace>(); }
export function WorkspaceLayout() {
  const { orgId } = useParams();
  const organization = useResource<Organization>('/organizations/' + orgId);
  const auth = useAuth();
  const [error, setError] = useState<unknown>();
  if (organization.error) return <div className="public-shell"><Brand /><ErrorMessage error={organization.error} retry={organization.reload} /><Link to="/app">Back to your businesses</Link></div>;
  if (!organization.data) return <div className="public-shell"><Loading /></div>;
  const org = organization.data;
  const root = '/app/organizations/' + org.id;
  const workspace = { organization: org, base: '/organizations/' + org.id, canEdit: org.role !== 'EMPLOYEE', refresh: organization.reload };
  return <div className="workspace">
    <header className="workspace-header"><Brand /><div className="workspace-account"><span>{auth.user?.displayName}</span><button className="text-button" onClick={() => void auth.logout().catch(setError)}>Sign out</button></div></header>
    <aside className="sidebar"><div className="business-label">YOUR WORKSPACE</div><h2>{org.name}</h2><Badge>{roleNames[org.role]}</Badge><Link className="switch-business" to="/app">Switch business ↗</Link>
      <nav aria-label="Business navigation">
        <NavLink to={root + '/items'}>▤ <span>Catalog</span></NavLink>
        <NavLink to={root + '/locations'}>▦ <span>Locations</span></NavLink>
        {org.role === 'OWNER' && <NavLink to={root + '/people'}>♧ <span>People</span></NavLink>}
        {org.role !== 'EMPLOYEE' && <NavLink to={root + '/activity'}>◷ <span>Activity</span></NavLink>}
        {org.role === 'OWNER' && <NavLink to={root + '/settings'}>⚙ <span>Settings</span></NavLink>}
      </nav><p className="sidebar-note">Everything in its place.<br />Everyone on the same page.</p>
    </aside>
    <main className="workspace-main"><ErrorMessage error={error} /><Outlet key={org.id} context={workspace} /></main>
  </div>;
}
export function OrganizationSettings() {
  const { organization, base, refresh } = useWorkspace();
  const navigate = useNavigate();
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  if (organization.role !== 'OWNER') return <ErrorMessage error={new Error('Only an Owner can change organization settings.')} />;
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setPending(true); setError(undefined);
    try {
      await send(base, 'PATCH', { name: String(new FormData(event.currentTarget).get('name')), expectedVersion: organization.version });
      refresh(); navigate('/app/organizations/' + organization.id + '/settings');
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  return <><PageHeader eyebrow="YOUR BUSINESS" title="Settings" description="Keep your workspace details up to date." /><form className="panel narrow" onSubmit={save}><h2>Organization details</h2><ErrorMessage error={error} retry={refresh} /><Field label="Business name"><input name="name" defaultValue={organization.name} maxLength={120} required /></Field><button className="button primary" disabled={pending}>{pending ? 'Saving…' : 'Save changes'}</button></form></>;
}
