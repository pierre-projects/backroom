import { useEffect, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { api, query, send } from './api';
import { useAuth } from './auth';
import { Badge, Dialog, Empty, ErrorMessage, Field, Loading, PageHeader, Pager, SuccessMessage, useResource } from './components';
import { Icon } from './icons';
import { formatQuantity, useStockLocations } from './stock';
import type { Item, Page, RequestStatus, RestockRequest, Unit } from './types';
import { useWorkspace } from './workspace';

export const statusNames: Record<RequestStatus, string> = {
  SUBMITTED: 'Submitted', APPROVED: 'Approved', PARTIALLY_RECEIVED: 'Partly received', FULFILLED: 'Received',
  REJECTED: 'Rejected', CANCELLED: 'Cancelled', CLOSED_SHORT: 'Closed short',
};
const statusTones: Record<RequestStatus, 'neutral' | 'success' | 'warning' | 'accent'> = {
  SUBMITTED: 'warning', APPROVED: 'accent', PARTIALLY_RECEIVED: 'accent', FULFILLED: 'success', REJECTED: 'neutral', CANCELLED: 'neutral', CLOSED_SHORT: 'neutral',
};
export function RequestBadge({ status }: { status: RequestStatus }) { return <Badge tone={statusTones[status]}>{statusNames[status]}</Badge>; }

const relative = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
export function ago(iso: string) {
  const seconds = (new Date(iso).getTime() - Date.now()) / 1000;
  const steps: [Intl.RelativeTimeFormatUnit, number][] = [['day', 86400], ['hour', 3600], ['minute', 60]];
  for (const [unit, size] of steps) if (Math.abs(seconds) >= size) return relative.format(Math.round(seconds / size), unit);
  return 'just now';
}
const path = (request: RestockRequest) => request.parentName ? request.parentName + ' › ' + request.locationName : request.locationName;
export function progress(request: RestockRequest) {
  const unit = request.unit;
  if (!request.approved) return formatQuantity(request.requested, unit) + ' requested';
  return formatQuantity(request.received, 'EACH') + ' of ' + formatQuantity(request.approved, unit) + ' received';
}

type Filter = 'NEEDS_REVIEW' | 'TO_RECEIVE' | 'MINE' | 'OPEN' | 'ALL';
const filterNames: Record<Filter, string> = { NEEDS_REVIEW: 'Needs review', TO_RECEIVE: 'To receive', MINE: 'Mine', OPEN: 'Open', ALL: 'All' };

export function RequestsPage() {
  const { base, canEdit, organization } = useWorkspace();
  const root = '/app/organizations/' + organization.id;
  const [params, setParams] = useSearchParams();
  const filters: Filter[] = canEdit ? ['NEEDS_REVIEW', 'TO_RECEIVE', 'MINE', 'OPEN', 'ALL'] : ['TO_RECEIVE', 'MINE', 'OPEN', 'ALL'];
  const filter = (filters.find(value => value === params.get('filter')) ?? (canEdit ? 'NEEDS_REVIEW' : 'TO_RECEIVE')) as Filter;
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const navigate = useNavigate();
  const requests = useResource<Page<RestockRequest>>(query(base + '/requests', { filter, page }));
  const empty: Record<Filter, string> = {
    NEEDS_REVIEW: 'Nothing is waiting for a decision.', TO_RECEIVE: 'No approved requests are waiting for a delivery.',
    MINE: "You haven't asked for anything yet.", OPEN: 'No open requests.', ALL: 'No requests yet.',
  };
  return <>
    <PageHeader title="Requests" description="Ask for more stock, review requests, and record what arrives.">
      <button type="button" className="button primary" onClick={() => setCreating(true)}><Icon name="plus" />New request</button>
    </PageHeader>
    <nav className="filter-chips" aria-label="Filter requests">{filters.map(value =>
      <button key={value} type="button" className={'chip' + (value === filter ? ' active' : '')} aria-pressed={value === filter}
        onClick={() => { setPage(0); setParams({ filter: value }, { replace: true }); }}>{filterNames[value]}</button>)}</nav>
    <ErrorMessage error={requests.error} retry={requests.reload} />
    {!requests.data && !requests.error && <div className="panel table-panel"><Loading variant="list" label="Loading requests" /></div>}
    {requests.data && (requests.data.items.length === 0
      ? <Empty icon="receive" title={filterNames[filter]}>{empty[filter]}</Empty>
      : <div className="panel table-panel"><ul className="request-list">{requests.data.items.map(request => <li key={request.id}>
        <Link className="request-row" to={root + '/requests/' + request.id}>
          <span className="request-main"><span className="request-title"><span className="mono request-label">{request.label}</span><strong>{request.itemName}</strong></span>
            <span className="cell-secondary">{path(request)} · {progress(request)}</span>
            <span className="cell-secondary">{request.requesterName} · {ago(request.createdAt)}</span></span>
          <RequestBadge status={request.status} />
          <Icon name="chevronRight" />
        </Link></li>)}</ul>
        <Pager data={requests.data} onPage={setPage} /></div>)}
    {creating && <NewRequestDialog close={() => setCreating(false)} done={request => navigate(root + '/requests/' + request.id)} />}
  </>;
}

/**
 * Ask for more of one item at one location. Shows any open requests for the same place first,
 * so people don't ask twice for the same thing.
 */
export function NewRequestDialog({ item, locationId, quantity, close, done }: {
  item?: { id: string; name: string; unit: Unit }; locationId?: string; quantity?: string; close: () => void; done: (request: RestockRequest) => void;
}) {
  const { base } = useWorkspace();
  const locations = useStockLocations(base);
  const [chosen, setChosen] = useState(item);
  const [where, setWhere] = useState(locationId ?? '');
  const [results, setResults] = useState<Item[]>();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>();
  const outstanding = useResource<Page<RestockRequest>>(chosen && where ? query(base + '/requests', { filter: 'OPEN', itemId: chosen.id, locationId: where, size: 5 }) : null);
  async function search(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    const q = event ? String(new FormData(event.currentTarget).get('q') ?? '') : '';
    try { setResults((await api<Page<Item>>(base + '/items?size=20&q=' + encodeURIComponent(q.trim()))).items); } catch (value) { setError(value); }
  }
  useEffect(() => { if (!item) void search(); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!chosen) return;
    const form = new FormData(event.currentTarget);
    setPending(true); setError(undefined);
    try {
      done(await send<RestockRequest>(base + '/requests', 'POST', {
        itemId: chosen.id, locationId: where, quantity: String(form.get('quantity')).trim().replace(',', '.'), note: String(form.get('note') ?? '').trim() || null,
      }));
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  const open = outstanding.data?.items ?? [];
  return <Dialog title="Request more stock" close={close}>
    {!chosen ? <>
      <p>Which item do you need more of?</p>
      <form className="search-form dialog-search" role="search" onSubmit={event => void search(event)}>
        <div className="search-input"><Icon name="search" /><input name="q" type="search" aria-label="Search items" placeholder="Search by name or SKU" /></div>
        <button className="button secondary">Search</button>
      </form>
      <ErrorMessage error={error} />
      <ul className="record-list pick-list">{results?.filter(entry => !entry.archived).map(entry => <li key={entry.id}>
        <span><strong>{entry.name}</strong><span className="cell-secondary">{entry.sku ?? 'No SKU'} · {formatQuantity(entry.onHand, entry.unit)} on hand</span></span>
        <button type="button" className="button secondary compact" onClick={() => setChosen({ id: entry.id, name: entry.name, unit: entry.unit })}>Choose<span className="sr-only"> {entry.name}</span></button>
      </li>)}</ul>
    </> : <form onSubmit={submit}>
      <p className="dialog-subject">{chosen.name}</p>
      <ErrorMessage error={error ?? locations.error} />
      <Field label="Deliver to"><select name="location" required value={where} onChange={event => setWhere(event.target.value)}>
        <option value="">Choose a location</option>
        {locations.leaves?.map(choice => <option key={choice.id} value={choice.id}>{choice.label}</option>)}
      </select></Field>
      {open.length > 0 && <div className="notice warning-message" role="status"><Icon name="alert" /><div>
        <p><strong>{open.length === 1 ? 'There is already an open request here' : 'There are already ' + open.length + ' open requests here'}</strong></p>
        <p>{open.map(request => request.label + ' asks for ' + formatQuantity(request.approved ?? request.requested, request.unit) + ' (' + statusNames[request.status].toLowerCase() + ')').join('; ')}. Submit anyway only if you need more.</p>
      </div></div>}
      <Field label={'Quantity' + (chosen.unit === 'EACH' ? '' : ' (' + formatQuantity('1', chosen.unit).replace(/^1 /, '') + ')')}>
        <input name="quantity" defaultValue={quantity} inputMode={chosen.unit === 'EACH' ? 'numeric' : 'decimal'} autoComplete="off" required pattern={chosen.unit === 'EACH' ? '[0-9]+' : '[0-9]+([.,][0-9]{1,3})?'} />
      </Field>
      <Field label="Note (optional)"><input name="note" maxLength={500} autoComplete="off" placeholder="For the weekend rush" /></Field>
      <div className="form-actions"><button type="button" className="button secondary" onClick={close}>Cancel</button>
        <button className="button primary" disabled={pending || !where}>{pending ? 'Sending…' : open.length ? 'Submit anyway' : 'Submit request'}</button></div>
    </form>}
  </Dialog>;
}

type RequestAction = 'approve' | 'reject' | 'cancel' | 'receive' | 'close';

export function RequestDetailPage() {
  const { requestId } = useParams();
  const { base, canEdit, organization } = useWorkspace();
  const auth = useAuth();
  const root = '/app/organizations/' + organization.id;
  const request = useResource<RestockRequest>(base + '/requests/' + requestId);
  const [action, setAction] = useState<RequestAction>();
  const [status, setStatus] = useState('');
  if (request.error) return <ErrorMessage error={request.error} retry={request.reload} />;
  if (!request.data) return <Loading />;
  const value = request.data;
  const unit = value.unit;
  const mine = value.requesterId === auth.user?.id;
  const receiving = value.status === 'APPROVED' || value.status === 'PARTIALLY_RECEIVED';
  const finished = (message: string) => { setAction(undefined); setStatus(message); request.reload(); };
  const quantity = (text: string | null) => text === null ? '—' : formatQuantity(text, unit);
  return <>
    <PageHeader back={{ to: root + '/requests', label: 'Requests' }} title={value.label + ' · ' + value.itemName}>
      {receiving && !value.itemArchived && <button type="button" className="button primary" onClick={() => setAction('receive')}><Icon name="receive" />Record delivery</button>}
      {canEdit && value.status === 'SUBMITTED' && <><button type="button" className="button primary" onClick={() => setAction('approve')}>Approve</button>
        <button type="button" className="button secondary" onClick={() => setAction('reject')}>Reject</button></>}
      {value.status === 'SUBMITTED' && (mine || canEdit) && <button type="button" className="button secondary" onClick={() => setAction('cancel')}>Cancel request</button>}
      {canEdit && receiving && <button type="button" className="button secondary" onClick={() => setAction('close')}>Close short</button>}
    </PageHeader>
    <p className="location-meta"><RequestBadge status={value.status} /></p>
    {status && <SuccessMessage>{status}</SuccessMessage>}
    <dl className="detail-grid request-quantities">
      <div><dt>Requested</dt><dd className="numeric">{quantity(value.requested)}</dd></div>
      <div><dt>Approved</dt><dd className="numeric">{quantity(value.approved)}</dd></div>
      <div><dt>Received</dt><dd className="numeric">{quantity(value.received)}</dd></div>
      <div><dt>Still expected</dt><dd className="numeric">{receiving ? quantity(value.remaining) : '—'}</dd></div>
    </dl>
    <section className="panel" aria-labelledby="request-about">
      <h2 className="section-heading" id="request-about">Details</h2>
      <dl className="facts-list">
        <div><dt>Item</dt><dd><Link to={root + '/items/' + value.itemId}>{value.itemName}</Link>{value.sku && <span className="muted mono"> {value.sku}</span>}</dd></div>
        <div><dt>Deliver to</dt><dd><Link to={root + '/locations/' + value.locationId}>{path(value)}</Link></dd></div>
        {value.note && <div><dt>Note</dt><dd>{value.note}</dd></div>}
      </dl>
    </section>
    <section className="panel" aria-labelledby="request-history">
      <h2 className="section-heading" id="request-history">History</h2>
      <ol className="timeline">
        <TimelineEntry icon="plus" title={'Requested ' + quantity(value.requested)} who={value.requesterName} when={value.createdAt} />
        {value.decidedAt && <TimelineEntry icon={value.status === 'REJECTED' ? 'close' : 'check'} who={value.decidedByName} when={value.decidedAt}
          title={value.status === 'REJECTED' ? 'Rejected' : 'Approved ' + quantity(value.approved) + (value.approved !== value.requested ? ' (asked for ' + quantity(value.requested) + ')' : '')}
          detail={value.decisionNote} />}
        {value.receipts.map(receipt => <TimelineEntry key={receipt.id} icon="receive" who={receipt.receiverName} when={receipt.receivedAt}
          title={'Delivery of ' + quantity(receipt.quantity)} detail={receipt.reversed ? 'Reversed in stock history; no longer counted.' : undefined}
          extra={<Link to={root + '/items/' + value.itemId}>See stock history</Link>} muted={receipt.reversed} />)}
        {value.closedAt && <TimelineEntry icon="close" who={value.closedByName} when={value.closedAt}
          title={value.status === 'CANCELLED' ? 'Cancelled' : 'Closed short at ' + quantity(value.received)} detail={value.closeReason} />}
      </ol>
    </section>
    {action && <RequestActionDialog action={action} request={value} close={() => setAction(undefined)} done={finished} />}
  </>;
}

function TimelineEntry({ icon, title, who, when, detail, extra, muted }: {
  icon: 'plus' | 'check' | 'close' | 'receive'; title: string; who: string | null; when: string; detail?: string | null; extra?: ReactNode; muted?: boolean;
}) {
  return <li className={muted ? 'muted-entry' : undefined}>
    <span className="activity-icon" aria-hidden="true"><Icon name={icon} /></span>
    <div><strong>{title}</strong><p>{who} · <time dateTime={when}>{new Date(when).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</time></p>
      {detail && <p className="timeline-detail">{detail}</p>}{extra}</div>
  </li>;
}

function RequestActionDialog({ action, request, close, done }: { action: RequestAction; request: RestockRequest; close: () => void; done: (message: string) => void }) {
  const { base } = useWorkspace();
  const [operationKey] = useState(() => crypto.randomUUID());
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>();
  const unit = request.unit;
  const unitLabel = unit === 'EACH' ? '' : ' (' + formatQuantity('1', unit).replace(/^1 /, '') + ')';
  const titles: Record<RequestAction, string> = {
    approve: 'Approve ' + request.label + '?', reject: 'Reject ' + request.label + '?', cancel: 'Cancel ' + request.label + '?',
    receive: 'Record a delivery for ' + request.label, close: 'Close ' + request.label + ' short?',
  };
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const text = (name: string) => String(form.get(name) ?? '').trim();
    const amount = text('quantity').replace(',', '.');
    setPending(true); setError(undefined);
    try {
      const url = base + '/requests/' + request.id;
      if (action === 'approve') {
        await send(url + '/approve', 'POST', { expectedVersion: request.version, approvedQuantity: amount || null, note: text('note') || null });
        done('Approved ' + formatQuantity(amount || request.requested, unit) + '. Anyone can now record the delivery.');
      } else if (action === 'reject') {
        await send(url + '/reject', 'POST', { expectedVersion: request.version, reason: text('reason') });
        done('Request rejected.');
      } else if (action === 'cancel') {
        await send(url + '/cancel', 'POST', { expectedVersion: request.version });
        done('Request cancelled.');
      } else if (action === 'receive') {
        await send(url + '/receipts', 'POST', { operationKey, quantity: amount, note: text('note') || null });
        done('Recorded ' + formatQuantity(amount, unit) + '. Stock at ' + path(request) + ' is updated.');
      } else {
        await send(url + '/close-short', 'POST', { expectedVersion: request.version, reason: text('reason') });
        done('Closed short at ' + formatQuantity(request.received, unit) + '.');
      }
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  const quantityInput = (defaultValue: string) => <input name="quantity" defaultValue={defaultValue} inputMode={unit === 'EACH' ? 'numeric' : 'decimal'} autoComplete="off"
    required={action === 'receive'} pattern={unit === 'EACH' ? '[0-9]+' : '[0-9]+([.,][0-9]{1,3})?'} />;
  const labels: Record<RequestAction, string> = { approve: 'Approve', reject: 'Reject', cancel: 'Cancel request', receive: 'Record delivery', close: 'Close short' };
  return <Dialog title={titles[action]} close={close}>
    <p className="dialog-subject">{request.itemName} · {path(request)}</p>
    <form onSubmit={submit}>
      <ErrorMessage error={error} />
      {action === 'approve' && <>
        <Field label={'Approved quantity' + unitLabel}>{quantityInput(request.requested)}<small>{request.requesterName} asked for {formatQuantity(request.requested, unit)}. You can approve a different amount; it can't be changed later.</small></Field>
        <Field label="Note (optional)"><input name="note" maxLength={500} autoComplete="off" /></Field>
      </>}
      {action === 'receive' && <>
        <Field label={'Quantity delivered' + unitLabel}>{quantityInput(request.remaining)}<small>{formatQuantity(request.remaining, unit)} still expected. Record exactly what arrived; extra stock goes in as an ordinary receipt.</small></Field>
        <Field label="Note (optional)"><input name="note" maxLength={500} autoComplete="off" placeholder="Delivery note number" /></Field>
      </>}
      {(action === 'reject' || action === 'close') && <Field label="Reason"><textarea name="reason" required maxLength={500} rows={3}
        placeholder={action === 'reject' ? 'Enough in the walk-in cooler' : 'Supplier is out of stock'} /><small>{action === 'reject' ? 'The requester sees this.' : 'Shown on the request so everyone knows why the rest isn’t coming.'}</small></Field>}
      {action === 'cancel' && <p>The request will be withdrawn. Nothing changes in stock.</p>}
      <div className="form-actions"><button type="button" className="button secondary" data-autofocus={action === 'cancel' || action === 'reject' || action === 'close' ? true : undefined} onClick={close}>Back</button>
        <button className={'button ' + (action === 'reject' || action === 'cancel' || action === 'close' ? 'danger' : 'primary')} disabled={pending}>{pending ? 'Saving…' : labels[action]}</button></div>
    </form>
  </Dialog>;
}

/** Open requests for one item, shown on the item page. */
export function ItemRequestsPanel({ itemId, onNew }: { itemId: string; onNew?: () => void }) {
  const { base, organization } = useWorkspace();
  const requests = useResource<Page<RestockRequest>>(query(base + '/requests', { filter: 'OPEN', itemId, size: 5 }));
  const root = '/app/organizations/' + organization.id;
  return <section className="panel table-panel" aria-labelledby={'requests-' + itemId}>
    <div className="panel-header"><div><h2 id={'requests-' + itemId}>Open requests</h2><p>Stock asked for and not yet delivered.</p></div>
      {onNew && <button type="button" className="button secondary compact" onClick={onNew}><Icon name="plus" />Request more</button>}</div>
    <ErrorMessage error={requests.error} retry={requests.reload} />
    {requests.data?.items.length === 0 && <p className="panel-body muted">No open requests for this item.</p>}
    {!!requests.data?.items.length && <ul className="request-list">{requests.data.items.map(request => <li key={request.id}>
      <Link className="request-row" to={root + '/requests/' + request.id}>
        <span className="request-main"><span className="request-title"><span className="mono request-label">{request.label}</span><strong>{path(request)}</strong></span>
          <span className="cell-secondary">{progress(request)} · {request.requesterName}</span></span>
        <RequestBadge status={request.status} /><Icon name="chevronRight" />
      </Link></li>)}</ul>}
  </section>;
}
