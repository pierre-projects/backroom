import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { query } from './api';
import { ErrorMessage, Loading, PageHeader, useResource } from './components';
import { Icon } from './icons';
import { ActivityList } from './people';
import { SearchForm } from './search';
import { NewRequestDialog, RequestBadge, ago, progress } from './requests';
import { formatQuantity, locationPath } from './stock';
import type { Activity, Item, LowStockRow, Member, Page, RequestSummary, RestockRequest, StockMovement, StorageLocation } from './types';
import { useWorkspace } from './workspace';

/** Per-browser memory for the parts of onboarding the server can't see (printing) and for hiding the checklist. */
function useLocalFlag(key: string): [boolean, () => void] {
  const read = () => { try { return localStorage.getItem(key) === '1'; } catch { return false; } };
  const [value, setValue] = useState(read);
  return [value, () => { try { localStorage.setItem(key, '1'); } catch { /* private mode: keep it for this visit only */ } setValue(true); }];
}

function Onboarding({ root, base, owner }: { root: string; base: string; owner: boolean }) {
  const { organization } = useWorkspace();
  const [hidden, hide] = useLocalFlag('backroom.onboarding.hidden.' + organization.id);
  const [printed, markPrinted] = useLocalFlag('backroom.onboarding.labels.' + organization.id);
  const locations = useResource<Page<StorageLocation>>(hidden ? null : query(base + '/locations', { size: 1 }));
  const items = useResource<Page<Item>>(hidden ? null : query(base + '/items', { size: 1, includeArchived: true }));
  const stock = useResource<Page<StockMovement>>(hidden ? null : query(base + '/inventory/movements', { size: 1 }));
  const members = useResource<Page<Member>>(hidden || !owner ? null : query(base + '/members', { size: 2 }));
  const invitations = useResource<Page<unknown>>(hidden || !owner ? null : query(base + '/invitations', { size: 1 }));
  if (hidden || !locations.data || !items.data || !stock.data || (owner && (!members.data || !invitations.data))) return null;
  const steps = [
    { done: locations.data.totalElements > 0, title: 'Add your storage locations', detail: 'Rooms first, then the shelves and bins inside them.', href: root + '/locations', action: 'Add a location' },
    { done: items.data.totalElements > 0, title: 'Add the items you stock', detail: 'Choose the unit you count each one in.', href: root + '/items', action: 'Add an item' },
    { done: stock.data.totalElements > 0, title: 'Record what is on the shelves', detail: 'Open an item and set its starting quantity at each location.', href: root + '/items', action: 'Open inventory' },
    { done: printed, title: 'Print labels and scan one', detail: 'Stick a QR label on each shelf, then try it from the Scan tab.', href: root + '/labels', action: 'Print labels', onClick: markPrinted },
    ...(owner ? [{ done: members.data!.totalElements > 1 || invitations.data!.totalElements > 0, title: 'Invite your team', detail: 'Send a one-time link to each manager and employee.', href: root + '/people', action: 'Invite staff' }] : []),
  ];
  const remaining = steps.filter(step => !step.done).length;
  if (remaining === 0) return null;
  const next = steps.find(step => !step.done)!;
  return <section className="panel table-panel onboarding" aria-labelledby="onboarding-heading">
    <div className="panel-header"><div><h2 id="onboarding-heading">Set up {organization.name}</h2><p>{steps.length - remaining} of {steps.length} done. Each step takes a few minutes.</p></div>
      <button type="button" className="text-button" onClick={hide}>Hide</button></div>
    <ol className="checklist">{steps.map(step => <li key={step.title} className={step.done ? 'done' : step === next ? 'current' : ''}>
      <span className="checklist-mark" aria-hidden="true">{step.done && <Icon name="check" className="small" />}</span>
      <div><strong>{step.title}</strong><span className="sr-only">{step.done ? ' (done)' : ''}</span><p>{step.detail}</p></div>
      {!step.done && <Link className={'button compact ' + (step === next ? 'primary' : 'secondary')} to={step.href} onClick={step.onClick}>{step.action}</Link>}
    </li>)}</ol>
  </section>;
}

function LowStockCard({ root, base }: { root: string; base: string }) {
  const navigate = useNavigate();
  const low = useResource<Page<LowStockRow>>(query(base + '/inventory/low-stock', { size: 5 }));
  // Open requests show next to the slot they cover, so nobody asks twice.
  const open = useResource<Page<RestockRequest>>(low.data?.items.length ? query(base + '/requests', { filter: 'OPEN', size: 100 }) : null);
  const [requesting, setRequesting] = useState<LowStockRow>();
  const covering = (row: LowStockRow) => open.data?.items.find(request => request.itemId === row.itemId && request.locationId === row.locationId);
  return <section className="panel table-panel home-card" aria-labelledby="low-heading">
    <div className="panel-header"><div><h2 id="low-heading">Low stock</h2><p>Locations below the minimum you set.</p></div>
      {!!low.data?.totalElements && <Link className="text-button" to={root + '/items?low=1'}>See all {low.data.totalElements}</Link>}</div>
    <ErrorMessage error={low.error ?? open.error} retry={low.reload} />
    {!low.data && !low.error && <Loading variant="list" label="Loading low stock" />}
    {low.data?.items.length === 0 && <p className="panel-body muted">Nothing is below its minimum. Set minimums from an item's stock rows to get warnings here.</p>}
    {!!low.data?.items.length && <ul className="record-list home-list">{low.data.items.map(row => {
      const request = covering(row);
      return <li key={row.itemId + row.locationId}>
        <div className="home-list-main">
          <Link className="record-link" to={root + '/items/' + row.itemId}>{row.itemName}</Link>
          <span className="cell-secondary">{locationPath(row)}</span>
          <span className="cell-secondary numeric"><strong className="warning-text">{formatQuantity(row.quantity, row.unit)}</strong> of {formatQuantity(row.minimum, row.unit)} · short {formatQuantity(row.shortfall, row.unit)}</span>
        </div>
        {request
          ? <Link className="button secondary compact" to={root + '/requests/' + request.id}>{request.label} open</Link>
          : <button type="button" className="button secondary compact" disabled={!open.data && !open.error} onClick={() => setRequesting(row)}>Request<span className="sr-only"> {row.itemName} for {locationPath(row)}</span></button>}
      </li>;
    })}</ul>}
    {requesting && <NewRequestDialog item={{ id: requesting.itemId, name: requesting.itemName, unit: requesting.unit }} locationId={requesting.locationId}
      quantity={requesting.shortfall} close={() => setRequesting(undefined)} done={request => navigate(root + '/requests/' + request.id)} />}
  </section>;
}

function RequestsCard({ root, base, manager }: { root: string; base: string; manager: boolean }) {
  const summary = useResource<RequestSummary>(base + '/requests/summary');
  const filter = manager ? 'NEEDS_REVIEW' : 'MINE';
  const list = useResource<Page<RestockRequest>>(query(base + '/requests', { filter, size: 5 }));
  const counts = manager
    ? [{ label: 'Need review', value: summary.data?.needsReview, filter: 'NEEDS_REVIEW' }, { label: 'To receive', value: summary.data?.toReceive, filter: 'TO_RECEIVE' }]
    : [{ label: 'To receive', value: summary.data?.toReceive, filter: 'TO_RECEIVE' }];
  return <section className="panel table-panel home-card" aria-labelledby="requests-heading">
    <div className="panel-header"><div><h2 id="requests-heading">Requests</h2><p>{manager ? 'Waiting for your decision, newest first.' : 'What you asked for and where it stands.'}</p></div>
      <Link className="text-button" to={root + '/requests'}>Open requests</Link></div>
    <ErrorMessage error={summary.error ?? list.error} retry={() => { summary.reload(); list.reload(); }} />
    <div className="stat-row">{counts.map(count => <Link key={count.filter} className="stat" to={root + '/requests?filter=' + count.filter}>
      <span className="stat-value numeric">{count.value ?? '–'}</span><span className="stat-label">{count.label}</span></Link>)}</div>
    {!list.data && !list.error && <Loading variant="list" label="Loading requests" />}
    {list.data?.items.length === 0 && <p className="panel-body muted">{manager ? 'No requests are waiting for review.' : 'You have no requests yet. Use Request more on an item when stock runs short.'}</p>}
    {!!list.data?.items.length && <ul className="record-list home-list">{list.data.items.map(request => <li key={request.id}>
      <div className="home-list-main">
        <Link className="record-link" to={root + '/requests/' + request.id}>{request.label} · {request.itemName}</Link>
        <span className="cell-secondary">{locationPath(request)} · {progress(request)} · {ago(request.createdAt)}</span>
      </div>
      <RequestBadge status={request.status} />
    </li>)}</ul>}
  </section>;
}

function ActivityCard({ root, base }: { root: string; base: string }) {
  const events = useResource<Page<Activity>>(query(base + '/audit-events', { size: 5 }));
  return <section className="panel table-panel home-card" aria-labelledby="recent-heading">
    <div className="panel-header"><div><h2 id="recent-heading">Recent activity</h2><p>The latest changes across the business.</p></div>
      <Link className="text-button" to={root + '/activity'}>All activity</Link></div>
    <ErrorMessage error={events.error} retry={events.reload} />
    {!events.data && !events.error && <Loading variant="list" label="Loading activity" />}
    {events.data?.items.length === 0 && <p className="panel-body muted">Changes to items, locations and people will be listed here.</p>}
    {!!events.data?.items.length && <ActivityList events={events.data.items} root={root} />}
  </section>;
}

export function HomePage() {
  const { base, canEdit, organization } = useWorkspace();
  const root = '/app/organizations/' + organization.id;
  const [requesting, setRequesting] = useState(false);
  const navigate = useNavigate();
  return <>
    <PageHeader title="Home" description={'What needs attention at ' + organization.name + '.'}>
      <Link className="button primary" to={root + '/scan'}><Icon name="scan" />Scan</Link>
      <button type="button" className="button secondary" onClick={() => setRequesting(true)}><Icon name="plus" />Request stock</button>
    </PageHeader>
    <div className="home-search"><SearchForm root={root} /></div>
    {canEdit && <Onboarding root={root} base={base} owner={organization.role === 'OWNER'} />}
    <div className="home-grid">
      <LowStockCard root={root} base={base} />
      <RequestsCard root={root} base={base} manager={canEdit} />
    </div>
    {canEdit && <ActivityCard root={root} base={base} />}
    {requesting && <NewRequestDialog close={() => setRequesting(false)} done={request => navigate(root + '/requests/' + request.id)} />}
  </>;
}
