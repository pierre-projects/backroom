import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router';
import { send } from './api';
import { Badge, Dialog, ErrorMessage, Field, Loading, PageHeader, Pager, useResource } from './components';
import type { Activity, CreatedInvitation, Invitation, Member, Page, Role } from './types';
import { roleNames } from './types';
import { useWorkspace } from './workspace';
import { useAuth } from './auth';

export function PeoplePage() {
  const { base, organization, refresh } = useWorkspace();
  const auth = useAuth();
  const [memberPage, setMemberPage] = useState(0);
  const [invitePage, setInvitePage] = useState(0);
  const members = useResource<Page<Member>>(organization.role === 'OWNER' ? base + '/members?page=' + memberPage : null);
  const invitations = useResource<Page<Invitation>>(organization.role === 'OWNER' ? base + '/invitations?page=' + invitePage : null);
  const [showInvite, setShowInvite] = useState(false);
  const [created, setCreated] = useState<CreatedInvitation>();
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  const [removing, setRemoving] = useState<Member>();
  const [changing, setChanging] = useState<{ member: Member; role: Role }>();
  if (organization.role !== 'OWNER') return <ErrorMessage error={new Error('Only an Owner can manage people and invitations.')} />;
  async function invite(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setPending(true); setError(undefined);
    try { setCreated(await send<CreatedInvitation>(base + '/invitations', 'POST', { role: new FormData(event.currentTarget).get('role') })); invitations.reload(); }
    catch (value) { setError(value); } finally { setPending(false); }
  }
  async function membershipChange() {
    setPending(true); setError(undefined);
    try {
      if (removing) await send(base + '/members/' + removing.userId, 'DELETE');
      if (changing) await send(base + '/members/' + changing.member.userId, 'PATCH', { role: changing.role });
      const self = removing?.userId === auth.user?.id || changing?.member.userId === auth.user?.id;
      setRemoving(undefined); setChanging(undefined); members.reload();
      if (self) refresh();
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  const inviteUrl = created ? new URL(created.invitePath, window.location.origin).href : '';
  return <><PageHeader eyebrow="THE PEOPLE BEHIND IT" title="People" description="Give your team the right access to your business."><button className="button primary" onClick={() => { setShowInvite(true); setCreated(undefined); setError(undefined); setCopied(false); }}>+ Invite staff</button></PageHeader>
    <ErrorMessage error={error ?? members.error} retry={members.error ? members.reload : undefined} />
    <section className="panel table-panel"><h2 className="table-title">Team members</h2>{!members.data && !members.error && <Loading />}<table><thead><tr><th>Person</th><th>Role</th><th>Status</th><th><span className="sr-only">Actions</span></th></tr></thead><tbody>{members.data?.items.map(member => <tr key={member.id}><td><strong>{member.displayName}{member.userId === auth.user?.id ? ' (you)' : ''}</strong><span className="cell-secondary">{member.email}</span></td><td>{member.active ? <select aria-label={'Role for ' + member.email} value={member.role} disabled={pending} onChange={event => { setChanging({ member, role: event.target.value as Role }); setError(undefined); }}>{Object.entries(roleNames).map(([role, label]) => <option key={role} value={role}>{label}</option>)}</select> : roleNames[member.role]}</td><td><Badge>{member.active ? 'Active' : 'Inactive'}</Badge></td><td>{member.active && <button className="text-button danger" disabled={pending} onClick={() => { setRemoving(member); setError(undefined); }}>Remove access</button>}</td></tr>)}</tbody></table>{members.data && <Pager data={members.data} onPage={setMemberPage} />}</section>
    <section className="panel table-panel"><h2 className="table-title">Invitations</h2><ErrorMessage error={invitations.error} retry={invitations.reload} />{invitations.data?.items.length === 0 && <p className="panel-padding muted">No invitations yet. Invite an employee or manager to get started.</p>}<table><thead><tr><th>Role</th><th>Expires</th><th>Status</th><th><span className="sr-only">Actions</span></th></tr></thead><tbody>{invitations.data?.items.map(invitation => <tr key={invitation.id}><td>{roleNames[invitation.role]}</td><td>{new Date(invitation.expiresAt).toLocaleDateString()}</td><td><Badge>{invitation.status.toLowerCase()}</Badge></td><td>{invitation.status === 'PENDING' && <button className="text-button danger" disabled={pending} onClick={() => { setPending(true); void send(base + '/invitations/' + invitation.id + '/revoke', 'POST').then(invitations.reload).catch(setError).finally(() => setPending(false)); }}>Revoke</button>}</td></tr>)}</tbody></table>{invitations.data && <Pager data={invitations.data} onPage={setInvitePage} />}</section>
    {showInvite && <Dialog title="Invite staff" close={() => setShowInvite(false)}>{created ? <><p>Share this link with the person you want to join as <strong>{roleNames[created.invitation.role]}</strong>. Anyone signed in with this link can accept it once.</p><Field label="Invitation link"><input readOnly value={inviteUrl} onFocus={event => event.currentTarget.select()} /></Field><p className="field-help">Expires in seven days. Save or copy the link now; it will not be shown again.</p><ErrorMessage error={error} /><button className="button primary" onClick={() => { void navigator.clipboard.writeText(inviteUrl).then(() => setCopied(true)).catch(() => setError(new Error('Copy is unavailable. Select and copy the link above.'))); }}>{copied ? 'Copied' : 'Copy invitation link'}</button></> : <form onSubmit={invite}><p>Invite someone to {organization.name}. You can promote a member to Owner after they join.</p><ErrorMessage error={error} /><Field label="Invitation role"><select name="role"><option value="EMPLOYEE">Employee — view catalog and locations</option><option value="MANAGER">Manager — manage catalog and locations</option></select></Field><button className="button primary" disabled={pending}>{pending ? 'Creating…' : 'Create invitation'}</button></form>}</Dialog>}
    {(removing || changing) && <Dialog title={removing ? 'Remove access' : 'Change role'} close={() => { setRemoving(undefined); setChanging(undefined); }}><p>{removing ? 'Remove access for ' + removing.displayName + '? Their past activity will remain available.' : 'Change ' + changing?.member.displayName + ' to ' + (changing ? roleNames[changing.role] : '') + '?'}</p><p className="field-help">An organization must always retain an active Owner.</p><ErrorMessage error={error} /><div className="form-actions"><button className="button secondary" onClick={() => { setRemoving(undefined); setChanging(undefined); }}>Cancel</button><button className="button primary" disabled={pending} onClick={() => void membershipChange()}>Confirm change</button></div></Dialog>}
  </>;
}
export function ActivityPage() {
  const { base, organization } = useWorkspace();
  const [page, setPage] = useState(0);
  const events = useResource<Page<Activity>>(organization.role !== 'EMPLOYEE' ? base + '/audit-events?page=' + page : null);
  if (organization.role === 'EMPLOYEE') return <ErrorMessage error={new Error('Only Owners and Managers can view organization activity.')} />;
  return <><PageHeader eyebrow="A SHARED HISTORY" title="Activity" description="Important changes, with the person and time behind each one." /><ErrorMessage error={events.error} retry={events.reload} />{!events.data && !events.error && <Loading />}<section className="panel"><ul className="activity-list">{events.data?.items.map(event => <li key={event.id}><span className="activity-dot" aria-hidden="true" /><div><strong>{event.action.replaceAll('_', ' ').toLowerCase()}</strong><p>{event.actorName}{event.metadata.name ? ' · ' + event.metadata.name : ''}{event.metadata.role ? ' · ' + roleNames[event.metadata.role as Role] : ''}</p>{event.resourceType === 'ITEM' && <Link to={'/app/organizations/' + organization.id + '/items/' + event.resourceId}>View item</Link>}{event.resourceType === 'LOCATION' && <Link to={'/app/organizations/' + organization.id + '/locations/' + event.resourceId}>View location</Link>}</div><time dateTime={event.occurredAt}>{new Date(event.occurredAt).toLocaleString()}</time></li>)}</ul>{events.data?.items.length === 0 && <p className="muted">Your organization's activity will appear here.</p>}</section>{events.data && <Pager data={events.data} onPage={setPage} />}</>;
}
