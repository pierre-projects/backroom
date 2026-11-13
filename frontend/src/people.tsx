import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router';
import { send } from './api';
import { Badge, Dialog, Empty, ErrorMessage, Field, Loading, PageHeader, Pager, useResource } from './components';
import { Icon } from './icons';
import type { IconName } from './icons';
import type { Activity, CreatedInvitation, Invitation, IssuedReset, Member, Page, Role, Unit } from './types';
import { formatQuantity } from './stock';
import { roleNames } from './types';
import { useWorkspace } from './workspace';
import { useAuth } from './auth';

const invitationTone: Record<string, 'neutral' | 'success' | 'warning' | 'danger'> = { PENDING: 'warning', ACCEPTED: 'success', REVOKED: 'neutral', EXPIRED: 'neutral' };

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
  const [resetting, setResetting] = useState<Member>();
  const [issued, setIssued] = useState<IssuedReset>();
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
  const closeConfirm = () => { setRemoving(undefined); setChanging(undefined); };
  async function issueReset() {
    if (!resetting) return;
    setPending(true); setError(undefined);
    try { setIssued(await send<IssuedReset>(base + '/members/' + resetting.userId + '/password-reset', 'POST')); }
    catch (value) { setError(value); } finally { setPending(false); }
  }
  const closeReset = () => { setResetting(undefined); setIssued(undefined); setCopied(false); setError(undefined); };
  const resetUrl = issued ? new URL(issued.resetPath, window.location.origin).href : '';
  const inviteUrl = created ? new URL(created.invitePath, window.location.origin).href : '';
  return <>
    <PageHeader title="People" description="Who can open this business, and what they can change."><button type="button" className="button primary" onClick={() => { setShowInvite(true); setCreated(undefined); setError(undefined); setCopied(false); }}><Icon name="plus" />Invite staff</button></PageHeader>
    <ErrorMessage error={error ?? members.error} retry={members.error ? members.reload : undefined} />

    <section className="panel table-panel" aria-labelledby="members-heading">
      <div className="panel-header"><div><h2 id="members-heading">Team members</h2><p>Owners manage people and settings. Managers edit the catalog and locations. Employees can view both.</p></div></div>
      {!members.data && !members.error && <Loading variant="list" label="Loading team members" />}
      {members.data && <table className="data-table">
        <thead><tr><th scope="col">Person</th><th scope="col">Role</th><th scope="col">Status</th><th scope="col"><span className="sr-only">Actions</span></th></tr></thead>
        <tbody>{members.data.items.map(member => <tr key={member.id}>
          <td><strong>{member.displayName}{member.userId === auth.user?.id ? ' (you)' : ''}</strong><span className="cell-secondary">{member.email}</span></td>
          <td className="cell-inline">{member.active ? <select aria-label={'Role for ' + member.displayName} value={member.role} disabled={pending} onChange={event => { setChanging({ member, role: event.target.value as Role }); setError(undefined); }}>{Object.entries(roleNames).map(([role, label]) => <option key={role} value={role}>{label}</option>)}</select> : roleNames[member.role]}</td>
          <td className="cell-inline">{member.active ? <Badge tone="success">Active</Badge> : <Badge>Inactive</Badge>}</td>
          <td className="cell-actions">{member.active && <span className="row-actions">
            {member.userId !== auth.user?.id && <button type="button" className="text-button" disabled={pending} onClick={() => { setResetting(member); setError(undefined); }}>Reset password<span className="sr-only"> for {member.displayName}</span></button>}
            <button type="button" className="text-button danger" disabled={pending} onClick={() => { setRemoving(member); setError(undefined); }}>Remove access</button></span>}</td>
        </tr>)}</tbody>
      </table>}
      {members.data && <Pager data={members.data} onPage={setMemberPage} />}
    </section>

    <section className="panel table-panel" aria-labelledby="invitations-heading">
      <div className="panel-header"><div><h2 id="invitations-heading">Invitations</h2><p>Links you've created. Each one works once and expires after seven days.</p></div></div>
      <ErrorMessage error={invitations.error} retry={invitations.reload} />
      {!invitations.data && !invitations.error && <Loading variant="list" label="Loading invitations" />}
      {invitations.data?.items.length === 0 && <p className="panel-body muted">No invitations yet.</p>}
      {!!invitations.data?.items.length && <table className="data-table">
        <thead><tr><th scope="col">Role</th><th scope="col">Expires</th><th scope="col">Status</th><th scope="col"><span className="sr-only">Actions</span></th></tr></thead>
        <tbody>{invitations.data.items.map(invitation => <tr key={invitation.id}>
          <td><strong>{roleNames[invitation.role]}</strong></td>
          <td className="numeric"><span className="mobile-only cell-secondary">Expires </span>{new Date(invitation.expiresAt).toLocaleDateString()}</td>
          <td className="cell-inline"><Badge tone={invitationTone[invitation.status] ?? 'neutral'}>{invitation.status.charAt(0) + invitation.status.slice(1).toLowerCase()}</Badge></td>
          <td className="cell-actions">{invitation.status === 'PENDING' && <button type="button" className="text-button danger" disabled={pending} onClick={() => { setPending(true); void send(base + '/invitations/' + invitation.id + '/revoke', 'POST').then(invitations.reload).catch(setError).finally(() => setPending(false)); }}>Revoke</button>}</td>
        </tr>)}</tbody>
      </table>}
      {invitations.data && <Pager data={invitations.data} onPage={setInvitePage} />}
    </section>

    {showInvite && <Dialog title="Invite staff" close={() => setShowInvite(false)}>{created ? <>
      <p>Send this link to the person joining as <strong>{roleNames[created.invitation.role]}</strong>. It works once, for whoever opens it while signed in.</p>
      <Field label="Invitation link"><input readOnly value={inviteUrl} onFocus={event => event.currentTarget.select()} /><small>Copy it now — it won't be shown again. Expires in seven days.</small></Field>
      <ErrorMessage error={error} />
      <div className="form-actions"><button type="button" className="button secondary" onClick={() => setShowInvite(false)}>Done</button><button type="button" className="button primary" onClick={() => { void navigator.clipboard.writeText(inviteUrl).then(() => setCopied(true)).catch(() => setError(new Error('Copy isn’t available here. Select the link above and copy it.'))); }}><Icon name={copied ? 'check' : 'copy'} />{copied ? 'Copied' : 'Copy link'}</button></div>
    </> : <form onSubmit={invite}>
      <p>Create a one-time link to join {organization.name}. You can make someone an Owner after they join.</p>
      <ErrorMessage error={error} />
      <Field label="Role"><select name="role"><option value="EMPLOYEE">Employee — find items, record stock changes and request supplies</option><option value="MANAGER">Manager — manage inventory and locations, review requests and oversee stock</option></select></Field>
      <div className="form-actions"><button type="button" className="button secondary" onClick={() => setShowInvite(false)}>Cancel</button><button className="button primary" disabled={pending}>{pending ? 'Creating…' : 'Create invitation'}</button></div>
    </form>}</Dialog>}

    {resetting && <Dialog title={'Reset password for ' + resetting.displayName + '?'} close={closeReset}>{issued ? <>
      <p>Give this link to {issued.displayName} in person, or by a message only they can read. It works once and expires in 24 hours.</p>
      <Field label="Reset link"><input readOnly value={resetUrl} onFocus={event => event.currentTarget.select()} /><small>Copy it now; it won't be shown again. Making another link cancels this one.</small></Field>
      <ErrorMessage error={error} />
      <div className="form-actions"><button type="button" className="button secondary" onClick={closeReset}>Done</button><button type="button" className="button primary" onClick={() => { void navigator.clipboard.writeText(resetUrl).then(() => setCopied(true)).catch(() => setError(new Error('Copy isn’t available here. Select the link above and copy it.'))); }}><Icon name={copied ? 'check' : 'copy'} />{copied ? 'Copied' : 'Copy link'}</button></div>
    </> : <>
      <p>This creates a one-time link that lets {resetting.displayName} choose a new password. Their current password keeps working until they use it; then every device they are signed in on is signed out.</p>
      <ErrorMessage error={error} />
      <div className="form-actions"><button type="button" className="button secondary" data-autofocus onClick={closeReset}>Cancel</button><button type="button" className="button primary" disabled={pending} onClick={() => void issueReset()}>{pending ? 'Creating…' : 'Create reset link'}</button></div>
    </>}</Dialog>}

    {(removing || changing) && <Dialog title={removing ? 'Remove ' + removing.displayName + '?' : 'Change role?'} close={closeConfirm}>
      <p>{removing ? 'They will lose access to ' + organization.name + ' right away. Their past activity stays in the history.' : 'Make ' + changing?.member.displayName + ' ' + (changing ? roleNames[changing.role] : '') + '?'}</p>
      <p className="field-help">A business always needs at least one active Owner.</p>
      <ErrorMessage error={error} />
      <div className="form-actions"><button type="button" className="button secondary" data-autofocus onClick={closeConfirm}>Cancel</button><button type="button" className={'button ' + (removing ? 'danger' : 'primary')} disabled={pending} onClick={() => void membershipChange()}>{pending ? 'Saving…' : removing ? 'Remove' : 'Change role'}</button></div>
    </Dialog>}
  </>;
}

/** Quantity and place for stock events, e.g. " · 2.5 kg · Stockroom › Shelf A". */
function stockDetail(event: Activity) {
  const { quantity, unit, location, from, to } = event.metadata;
  if (!quantity || !unit) return '';
  const amount = formatQuantity(quantity, unit as Unit);
  return ' · ' + amount + (location ? ' · ' + location : '') + (from && to ? ' · ' + from + ' → ' + to : '');
}
function activityIcon(event: Activity): IconName {
  if (event.resourceType === 'ITEM') return 'catalog';
  if (event.resourceType === 'LOCATION') return 'location';
  if (event.action.includes('MEMBER') || event.action.includes('INVITATION')) return 'people';
  return 'activity';
}
/** Audit events as a list; used by the Activity page and the Home card. */
export function ActivityList({ events, root }: { events: Activity[]; root: string }) {
  return <ul className="activity-list">{events.map(event => <li key={event.id}>
    <span className="activity-icon" aria-hidden="true"><Icon name={activityIcon(event)} /></span>
    <div><strong>{event.action.replaceAll('_', ' ').toLowerCase()}</strong>
      <p>{event.actorName}{event.metadata.name ? ' · ' + event.metadata.name : ''}{event.metadata.role ? ' · ' + roleNames[event.metadata.role as Role] : ''}{stockDetail(event)}</p>
      {event.resourceType === 'ITEM' && <Link to={root + '/items/' + event.resourceId}>View item</Link>}
      {event.resourceType === 'LOCATION' && <Link to={root + '/locations/' + event.resourceId}>View location</Link>}
      {event.resourceType === 'REQUEST' && <Link to={root + '/requests/' + event.resourceId}>View request {event.metadata.request}</Link>}</div>
    <time dateTime={event.occurredAt}>{new Date(event.occurredAt).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</time>
  </li>)}</ul>;
}
export function ActivityPage() {
  const { base, organization } = useWorkspace();
  const [page, setPage] = useState(0);
  const events = useResource<Page<Activity>>(organization.role !== 'EMPLOYEE' ? base + '/audit-events?page=' + page : null);
  if (organization.role === 'EMPLOYEE') return <ErrorMessage error={new Error('Only Owners and Managers can view organization activity.')} />;
  const root = '/app/organizations/' + organization.id;
  return <>
    <PageHeader title="Activity" description="Changes to this business, newest first." />
    <ErrorMessage error={events.error} retry={events.reload} />
    {!events.data && !events.error && <div className="panel table-panel"><Loading variant="list" label="Loading activity" /></div>}
    {events.data?.items.length === 0 && <Empty icon="activity" title="No activity yet">Changes to items, locations and people will be listed here.</Empty>}
    {!!events.data?.items.length && <section className="panel table-panel" aria-label="Activity">
      <ActivityList events={events.data.items} root={root} />
      <Pager data={events.data} onPage={setPage} />
    </section>}
  </>;
}
