import { RecordLink } from './navigation';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { query } from './api';
import { ErrorMessage, Loading, PageHeader, useResource } from './components';
import { Icon } from './icons';
import { labelDownloadKey } from './labels';
import { VisualTile, spaceFacts, spaceIcons } from './browse';
import { ActivityList } from './people';
import { SearchForm } from './search';
import { NewRequestDialog, RequestBadge, ago, progress } from './requests';
import { formatQuantity, locationPath } from './stock';
import type { Activity, Item, LowStockRow, Member, Page, RequestSummary, RestockRequest, StockMovement, StorageLocation } from './types';
import { useWorkspace } from './workspace';

function flagSet(key: string) {
  try { return localStorage.getItem(key) === '1'; } catch { return false; }
}

function Onboarding({ root, base, owner, hasWork, hasMovements }: {
  root: string; base: string; owner: boolean; hasWork: boolean; hasMovements: boolean;
}) {
  const { organization } = useWorkspace();
  const [expanded, setExpanded] = useState<boolean>();
  const open = expanded ?? (!hasWork && !flagSet('backroom.onboarding.hidden.' + organization.id));
  const printed = flagSet(labelDownloadKey(organization.id));
  const locations = useResource<Page<StorageLocation>>(query(base + '/locations', { size: 1 }));
  const items = useResource<Page<Item>>(query(base + '/items', { size: 1, includeArchived: true }));
  const members = useResource<Page<Member>>(owner ? query(base + '/members', { size: 2 }) : null);
  const invitations = useResource<Page<unknown>>(owner ? query(base + '/invitations', { size: 1 }) : null);
  const error = locations.error ?? items.error ?? members.error ?? invitations.error;
  if (error) return <ErrorMessage error={error} retry={() => { locations.reload(); items.reload(); members.reload(); invitations.reload(); }} />;
  if (!locations.data || !items.data || (owner && (!members.data || !invitations.data))) return null;
  const steps = [
    { done: locations.data.totalElements > 0, title: 'Add your storage locations', detail: 'Rooms first, then the shelves and bins inside them.', href: root + '/locations', action: 'Add a location' },
    { done: items.data.totalElements > 0, title: 'Add the items you stock', detail: 'Choose the unit you count each one in.', href: root + '/items', action: 'Add an item' },
    { done: hasMovements, title: 'Record what is on the shelves', detail: 'Open an item and set its starting quantity at each location.', href: root + '/items', action: 'Open inventory' },
    { done: printed, title: 'Download a label', detail: 'Choose a storage location and download its printable QR label.', href: root + (locations.data.totalElements ? '/locations?select=labels' : '/locations'), action: locations.data.totalElements ? 'Choose locations' : 'Open locations' },
    ...(owner ? [{ done: members.data!.totalElements > 1 || invitations.data!.totalElements > 0, title: 'Invite your team', detail: 'Send a one-time link to each manager and employee.', href: root + '/people', action: 'Invite staff' }] : []),
  ];
  const remaining = steps.filter(step => !step.done).length;
  if (remaining === 0) return null;
  const next = steps.find(step => !step.done)!;
  return <section className="panel table-panel onboarding" aria-labelledby="onboarding-heading">
    <div className="panel-header"><div><h2 id="onboarding-heading">{open ? 'Set up ' + organization.name : 'Continue setup · ' + remaining + ' remaining'}</h2><p>{steps.length - remaining} of {steps.length} done.</p></div>
      <button type="button" className="text-button" aria-expanded={open} aria-controls="setup-steps" onClick={() => setExpanded(!open)}>{open ? 'Collapse' : 'Expand'}</button></div>
    <ol id="setup-steps" className="checklist" hidden={!open}>{steps.map(step => <li key={step.title} className={step.done ? 'done' : step === next ? 'current' : ''}>
      <span className="checklist-mark" aria-hidden="true">{step.done && <Icon name="check" className="small" />}</span>
      <div><strong>{step.title}</strong><span className="sr-only">{step.done ? ' (done)' : ''}</span><p>{step.detail}</p></div>
      {!step.done && <Link className={'button compact ' + (step === next ? 'primary' : 'secondary')} to={step.href}>{step.action}</Link>}
    </li>)}</ol>
  </section>;
}

function LowStockCard({ root, base, low }: { root: string; base: string; low: ReturnType<typeof useResource<Page<LowStockRow>>> }) {
  const navigate = useNavigate();

  // Open requests show next to the slot they cover, so nobody asks twice.
  const open = useResource<Page<RestockRequest>>(low.data?.items.length ? query(base + '/requests', { filter: 'OPEN', size: 100 }) : null);
  const [requesting, setRequesting] = useState<LowStockRow>();
  const covering = (row: LowStockRow) => open.data?.items.find(request => request.itemId === row.itemId && request.locationId === row.locationId);
  return <section className="panel table-panel home-card" aria-labelledby="low-heading">
    <div className="panel-header"><div><h2 id="low-heading">Low stock</h2><p>Locations below the minimum you set.</p></div>
      {!!low.data?.totalElements && <Link className="text-button" to={root + '/items?low=1'}>See all low items</Link>}</div>
    <ErrorMessage error={low.error ?? open.error} retry={() => { low.reload(); open.reload(); }} />
    {!low.data && !low.error && <Loading variant="list" label="Loading low stock" />}
    {low.data?.items.length === 0 && <p className="panel-body muted">Nothing is below its minimum. Set minimums from an item's stock rows to get warnings here.</p>}
    {!!low.data?.items.length && <ul className="record-list home-list">{low.data.items.map(row => {
      const request = covering(row);
      return <li key={row.itemId + row.locationId}>
        <div className="home-list-main">
          <RecordLink from="Home" className="record-link" to={root + '/items/' + row.itemId}>{row.itemName}</RecordLink>
          <span className="cell-secondary">{locationPath(row)}</span>
          <span className="cell-secondary numeric"><strong className="warning-text">{formatQuantity(row.quantity, row.unit)}</strong> of {formatQuantity(row.minimum, row.unit)} · short {formatQuantity(row.shortfall, row.unit)}</span>
        </div>
        {request
          ? <RecordLink from="Home" className="button secondary compact" to={root + '/requests/' + request.id}>{request.label} open</RecordLink>
          : <button type="button" className="button secondary compact" disabled={!open.data && !open.error} onClick={() => setRequesting(row)}>Request<span className="sr-only"> {row.itemName} for {locationPath(row)}</span></button>}
      </li>;
    })}</ul>}
    {requesting && <NewRequestDialog item={{ id: requesting.itemId, name: requesting.itemName, unit: requesting.unit }} locationId={requesting.locationId}
      quantity={requesting.shortfall} close={() => setRequesting(undefined)} done={request => navigate(root + '/requests/' + request.id)} />}
  </section>;
}

function RequestsCard({ root, base, manager }: { root: string; base: string; manager: boolean }) {

  const filter = manager ? 'NEEDS_REVIEW' : 'MINE';
  const list = useResource<Page<RestockRequest>>(query(base + '/requests', { filter, size: 5 }));
  return <section className="panel table-panel home-card" aria-labelledby="requests-heading">
    <div className="panel-header"><div><h2 id="requests-heading">{manager ? 'Requests' : 'My requests'}</h2><p>{manager ? 'Waiting for your decision, newest first.' : 'What you asked for and where it stands.'}</p></div>
      <Link className="text-button" to={root + (manager ? '/requests' : '/requests?filter=MINE')}>Open requests</Link></div>
    <ErrorMessage error={list.error} retry={list.reload} />
    {!list.data && !list.error && <Loading variant="list" label="Loading requests" />}
    {list.data?.items.length === 0 && <p className="panel-body muted">{manager ? 'No requests are waiting for review.' : 'You have no requests yet. Use Request more on an item when stock runs short.'}</p>}
    {!!list.data?.items.length && <ul className="record-list home-list">{list.data.items.map(request => <li key={request.id}>
      <div className="home-list-main">
        <RecordLink from="Home" className="record-link" to={root + '/requests/' + request.id}>{request.label} · {request.itemName}</RecordLink>
        <span className="cell-secondary">{locationPath(request)} · {progress(request)} · {ago(request.createdAt)}</span>
      </div>
      <RequestBadge status={request.status} />
    </li>)}</ul>}
  </section>;
}

/** A quiet way into the physical spaces: the first top-level rooms, then all locations. Hidden until there are any. */
function SpacesStrip({ root, base }: { root: string; base: string }) {
  const rooms = useResource<Page<StorageLocation>>(query(base + '/locations', { rootsOnly: true, size: 6 }));
  if (!rooms.data?.items.length) return null;
  return <section className="spaces-strip" aria-labelledby="spaces-heading">
    <div className="spaces-strip-header"><h2 id="spaces-heading">Spaces</h2><Link className="text-button" to={root + '/locations'}>All locations</Link></div>
    <ul>{rooms.data.items.map(room => <li key={room.id}>
      <RecordLink from="Home" className="space-chip" to={root + '/locations/' + room.id}>
        <VisualTile size="sm" name={room.name} icon={spaceIcons[room.type]} />
        <span><strong>{room.name}</strong><span className="cell-secondary">{spaceFacts(room)}</span></span>
      </RecordLink></li>)}</ul>
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
  const low = useResource<Page<LowStockRow>>(query(base + '/inventory/low-stock', { size: 5 }));
  // The tile links to the Inventory low-stock filter, which lists items, so it counts items rather than low shelves.
  const lowItems = useResource<Page<Item>>(canEdit ? query(base + '/items', { lowStock: true, size: 1 }) : null);
  const summary = useResource<RequestSummary>(base + '/requests/summary');
  const stock = useResource<Page<StockMovement>>(canEdit ? query(base + '/inventory/movements', { size: 1 }) : null);
  const hasMovements = Boolean(stock.data?.totalElements);
  const hasWork = hasMovements || Boolean(summary.data?.needsReview);
  const setup = canEdit && stock.data && summary.data && <Onboarding root={root} base={base} owner={organization.role === 'OWNER'}
    hasWork={hasWork} hasMovements={hasMovements} />;
  return <>
    <PageHeader title="Home" description={canEdit ? 'What needs attention at ' + organization.name + '.' : 'Find stock or record a delivery.'} />
    {canEdit && !hasWork && setup}
    {!canEdit && <div className="shift-search"><SearchForm root={root} /><Link className="button primary" to={root + '/scan'}><Icon name="scan" />Scan</Link></div>}
    <div className="work-counts">
      {canEdit ? <>
        <Link className="stat" to={root + '/requests?filter=NEEDS_REVIEW'}><strong>{summary.data?.needsReview ?? '–'}</strong> Needs review</Link>
        <Link className="stat" to={root + '/items?low=1'}><strong>{lowItems.data?.totalElements ?? '–'}</strong> Low stock</Link>
      </> : <Link className="stat" to={root + '/requests?filter=TO_RECEIVE'}><strong>{summary.data?.toReceive ?? '–'}</strong> To receive</Link>}
    </div>
    {!canEdit && <SpacesStrip root={root} base={base} />}
    <ErrorMessage error={summary.error ?? stock.error ?? lowItems.error} retry={() => { summary.reload(); stock.reload(); lowItems.reload(); }} />
    {canEdit && <div className="shift-search"><SearchForm root={root} /><Link className="button secondary" to={root + '/scan'}><Icon name="scan" />Scan</Link></div>}
    <div className="home-grid">
      <RequestsCard root={root} base={base} manager={canEdit} />
      <LowStockCard root={root} base={base} low={low} />
    </div>
    {canEdit && <SpacesStrip root={root} base={base} />}
    <button type="button" className="button secondary home-request" onClick={() => setRequesting(true)}><Icon name="plus" />Request stock</button>
    {hasWork && setup}
    {canEdit && <ActivityCard root={root} base={base} />}
    {requesting && <NewRequestDialog close={() => setRequesting(false)} done={request => navigate(root + '/requests/' + request.id)} />}
  </>;
}
