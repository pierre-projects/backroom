import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { query, send } from './api';
import { Badge, Dialog, Empty, ErrorMessage, Field, Loading, PageHeader, Pager, SuccessMessage, useResource } from './components';
import { Icon } from './icons';
import type { Item, Page, StockBalance, StockMovement } from './types';
import { unitNames } from './types';
import { MovementList, ReverseDialog, SlotSheet, StockDialog, formatQuantity, locationPath, useStockLocations } from './stock';
import type { StockAction } from './stock';
import { CodesPanel } from './codes';
import { ItemRequestsPanel, NewRequestDialog } from './requests';
import { SelectionBar, useLabelSelection } from './labels';
import { useWorkspace } from './workspace';

function ItemForm({ item, saved, cancel, unitLocked = false }: { item?: Item; saved: (item: Item) => void; cancel: () => void; unitLocked?: boolean }) {
  const { base } = useWorkspace();
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setPending(true); setError(undefined);
    const data = new FormData(event.currentTarget);
    try {
      const result = await send<Item>(base + '/items' + (item ? '/' + item.id : ''), item ? 'PATCH' : 'POST', {
        name: data.get('name'), sku: data.get('sku') || null, unit: data.get('unit'), ...(item ? { expectedVersion: item.version } : {}),
      });
      saved(result);
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  return <form onSubmit={submit}>
    <ErrorMessage error={error} />
    <Field label="Item name"><input name="name" defaultValue={item?.name} maxLength={160} required /></Field>
    <Field label="SKU (optional)"><input name="sku" defaultValue={item?.sku ?? ''} maxLength={80} autoCapitalize="characters" /><small>Saved in uppercase. Stays reserved after the item is archived.</small></Field>
    <Field label="Base unit"><select name="unit" defaultValue={item?.unit ?? 'EACH'} disabled={unitLocked}>{Object.entries(unitNames).map(([value, name]) => <option value={value} key={value}>{name}</option>)}</select><small>{unitLocked ? 'Locked because stock has been recorded in this unit.' : 'The unit you count this item in. Packs or cases can be separate items.'}</small></Field>
    {unitLocked && <input type="hidden" name="unit" value={item?.unit} />}
    <div className="form-actions"><button className="button secondary" type="button" onClick={cancel}>Cancel</button><button className="button primary" disabled={pending}>{pending ? 'Saving…' : item ? 'Save item' : 'Create item'}</button></div>
  </form>;
}

export function CatalogPage() {
  const { base, canEdit, organization } = useWorkspace();
  const [page, setPage] = useState(0);
  const [q, setQ] = useState('');
  const [archived, setArchived] = useState(false);
  const [creating, setCreating] = useState(false);
  const navigate = useNavigate();
  const items = useResource<Page<Item>>(query(base + '/items', { page, q, includeArchived: archived }));
  const root = '/app/organizations/' + organization.id;
  const addButton = <button type="button" className="button primary" onClick={() => setCreating(true)}><Icon name="plus" />Add item</button>;
  const selection = useLabelSelection();
  return <>
    <PageHeader title="Inventory" description="What you stock, how much, and where.">{canEdit && <>{addButton}<button type="button" className="button secondary" aria-pressed={selection.selecting} onClick={selection.toggleMode}><Icon name="label" />{selection.selecting ? 'Cancel' : 'Print labels'}</button></>}</PageHeader>
    {selection.selecting && <SelectionBar count={selection.selected.length} noun="item" href={root + '/labels?items=' + selection.selected.join(',')} />}
    <div className="toolbar">
      <form className="search-form" role="search" onSubmit={event => { event.preventDefault(); setQ(String(new FormData(event.currentTarget).get('search')).trim()); setPage(0); }}>
        <div className="search-input"><Icon name="search" /><input name="search" type="search" aria-label="Search inventory" placeholder="Search by name or SKU" defaultValue={q} key={q} /></div>
        <button className="button secondary">Search</button>
      </form>
      <label className="checkbox"><input type="checkbox" checked={archived} onChange={event => { setArchived(event.target.checked); setPage(0); }} /> Include archived</label>
    </div>
    <ErrorMessage error={items.error} retry={items.reload} />
    {!items.data && !items.error && <div className="panel table-panel"><Loading variant="list" label="Loading items" /></div>}
    {items.data && (items.data.items.length ? <div className="panel table-panel">
      <table className="data-table">
        <thead><tr><th scope="col">Item</th><th scope="col">SKU</th><th scope="col" className="cell-number">On hand</th><th scope="col"><span className="sr-only">Open</span></th></tr></thead>
        <tbody>{items.data.items.map(item => <tr key={item.id} className={selection.selecting ? 'selectable' : 'linked'}>
          <td><span className="name-line">{selection.selecting
            ? <label className="select-line"><input type="checkbox" checked={selection.selected.includes(item.id)} disabled={item.archived} onChange={() => selection.toggle(item.id)} /><span className="record-link">{item.name}</span></label>
            : <Link className="record-link" to={root + '/items/' + item.id}>{item.name}</Link>}{item.archived && <Badge>Archived</Badge>}</span>
            <span className="cell-secondary mobile-only">{item.sku ? <span className="mono">{item.sku}</span> : 'No SKU'} · <span className="numeric">{formatQuantity(item.onHand, item.unit)}</span> on hand</span></td>
          <td className="desktop-only mono">{item.sku ?? <><span aria-hidden="true">—</span><span className="sr-only">No SKU</span></>}</td>
          <td className="desktop-only cell-number numeric">{formatQuantity(item.onHand, item.unit)}</td>
          <td className="cell-chevron"><Icon name="chevronRight" /></td>
        </tr>)}</tbody>
      </table>
      <Pager data={items.data} onPage={setPage} />
    </div> : q
      ? <Empty icon="search" title={'No items match “' + q + '”'} action={<button type="button" className="button secondary" onClick={() => { setQ(''); setPage(0); }}>Clear search</button>}>Check the spelling, or search by SKU instead.</Empty>
      : <Empty icon="catalog" title="No items yet" action={canEdit ? undefined : <Link className="button secondary" to={root + '/locations'}>View locations</Link>}>{canEdit ? 'Use Add item to create your first one and choose the unit you count it in.' : 'Items your team adds will appear here.'}</Empty>)}
    {creating && <Dialog title="Add item" close={() => setCreating(false)}><ItemForm cancel={() => setCreating(false)} saved={item => navigate(root + '/items/' + item.id)} /></Dialog>}
  </>;
}

export function ItemDetail() {
  const { itemId } = useParams();
  const { base, canEdit, organization } = useWorkspace();
  const navigate = useNavigate();
  const item = useResource<Item>(base + '/items/' + itemId);
  const balances = useResource<StockBalance[]>(base + '/inventory/balances?itemId=' + itemId);
  const [historyPage, setHistoryPage] = useState(0);
  const history = useResource<Page<StockMovement>>(query(base + '/inventory/movements', { itemId, page: historyPage, size: 10 }));
  const locations = useStockLocations(base);
  const [editing, setEditing] = useState(false);
  const [archiving, setArchiving] = useState(false);
  const [sheet, setSheet] = useState<StockBalance>();
  const [action, setAction] = useState<{ kind: StockAction; locationId?: string }>();
  const [reversing, setReversing] = useState<StockMovement>();
  const [addingPlace, setAddingPlace] = useState(false);
  const [requesting, setRequesting] = useState<{ locationId?: string }>();
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  const [status, setStatus] = useState('');
  const root = '/app/organizations/' + organization.id;
  const refresh = () => { item.reload(); balances.reload(); history.reload(); locations.reload(); };
  // A scan result can open this page with ?action=usage or ?action=receive; the sheet still needs confirmation.
  const [searchParams, setSearchParams] = useSearchParams();
  const requested = searchParams.get('action');
  useEffect(() => {
    if (!requested || !item.data || !balances.data) return;
    if ((requested === 'usage' || requested === 'receive' || requested === 'waste') && !item.data.archived) setAction({ kind: requested });
    if (requested === 'request' && !item.data.archived) setRequesting({});
    setSearchParams({}, { replace: true });
  }, [requested, item.data, balances.data, setSearchParams]);
  const finished = (message: string) => { setAction(undefined); setReversing(undefined); setStatus(message); refresh(); };
  async function mutate(path: string, method: string, data?: unknown, message = '') {
    setPending(true); setError(undefined); setStatus('');
    try { await send(path, method, data); refresh(); if (message) setStatus(message); return true; }
    catch (value) { setError(value); return false; }
    finally { setPending(false); }
  }
  async function addPlacement(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const locationId = String(new FormData(event.currentTarget).get('locationId'));
    if (await mutate(base + '/items/' + itemId + '/placements/' + locationId, 'PUT', undefined, locations.label(locationId) + ' added')) setAddingPlace(false);
  }
  if (item.error) return <ErrorMessage error={item.error} retry={item.reload} />;
  if (!item.data) return <Loading />;
  const value = item.data;
  const editable = canEdit && !value.archived;
  const slots = balances.data ?? [];
  const hasStock = slots.some(slot => Number(slot.quantity) > 0);
  const unitLocked = slots.some(slot => slot.hasHistory) || (history.data?.totalElements ?? 0) > 0;
  const unplaced = locations.leaves?.filter(choice => !slots.some(slot => slot.locationId === choice.id)) ?? [];
  return <>
    <PageHeader back={{ to: root + '/items', label: 'Inventory' }} title={value.name}>
      {!value.archived && <button type="button" className="button primary" onClick={() => setAction({ kind: 'receive' })}><Icon name="receive" />Receive stock</button>}
      {!value.archived && <button type="button" className="button secondary" onClick={() => setRequesting({})}><Icon name="plus" />Request more</button>}
      {editable && hasStock && <button type="button" className="button secondary" onClick={() => setAction({ kind: 'transfer' })}><Icon name="transfer" />Transfer</button>}
      {editable && <><button type="button" className="button secondary" onClick={() => setEditing(true)}>Edit item</button><button type="button" className="button secondary" onClick={() => setArchiving(true)}>Archive item</button></>}
    </PageHeader>
    <dl className="detail-grid">
      <div><dt>On hand</dt><dd className="numeric detail-total">{formatQuantity(value.onHand, value.unit)}</dd></div>
      <div><dt>SKU</dt><dd className="mono">{value.sku ?? 'Not set'}</dd></div>
      <div><dt>Base unit</dt><dd>{unitNames[value.unit]}</dd></div>
      {value.archived && <div><dt>Status</dt><dd><Badge tone="warning">Archived</Badge></dd></div>}
    </dl>
    {status && <SuccessMessage>{status}</SuccessMessage>}
    <ErrorMessage error={error} />
    <section className="panel table-panel" aria-labelledby="stock-heading">
      <div className="panel-header"><div><h2 id="stock-heading">Stock by location</h2><p>Where this item is kept and how much is there.</p></div></div>
      <ErrorMessage error={balances.error ?? locations.error} retry={refresh} />
      {!balances.data && !balances.error && <Loading variant="list" label="Loading stock" />}
      {balances.data?.length === 0 && <p className="panel-body muted">No locations yet.{value.archived ? '' : ' Use Receive stock to put this item on a shelf.'}</p>}
      {!!slots.length && <table className="data-table">
        <thead><tr><th scope="col">Location</th><th scope="col" className="cell-number">On hand</th><th scope="col"><span className="sr-only">Actions</span></th></tr></thead>
        <tbody>{slots.map(slot => <tr key={slot.locationId}>
          <td><span className="location-path">{slot.parentName && <span className="location-parent">{slot.parentName}<span aria-hidden="true"> ›</span></span>}<Link className="record-link" to={root + '/locations/' + slot.locationId}>{slot.locationName}</Link></span>
            <span className="cell-secondary mobile-only numeric">{formatQuantity(slot.quantity, value.unit)} on hand</span></td>
          <td className="desktop-only cell-number numeric">{formatQuantity(slot.quantity, value.unit)}</td>
          <td className="cell-actions">{!value.archived && <button type="button" className="button secondary compact" onClick={() => setSheet(slot)}>Update<span className="sr-only"> {locationPath(slot)}</span></button>}</td>
        </tr>)}</tbody>
      </table>}
      {editable && <div className="panel-footer">
        {addingPlace ? <form className="inline-form" onSubmit={addPlacement}>
          <Field label="Storage location"><select name="locationId" required disabled={!unplaced.length || pending}><option value="">Choose a location</option>{unplaced.map(choice => <option key={choice.id} value={choice.id}>{choice.label}</option>)}</select></Field>
          <button className="button primary" disabled={pending || !unplaced.length}>{pending ? 'Adding…' : 'Add placement'}</button>
          <button type="button" className="button ghost" onClick={() => setAddingPlace(false)}>Cancel</button>
          {locations.leaves && !unplaced.length && <p className="field-help">Every shelf and bin already lists this item.</p>}
        </form> : <button type="button" className="text-button" onClick={() => setAddingPlace(true)}><Icon name="plus" className="small" />Add an expected location</button>}
      </div>}
    </section>
    {!value.archived && <ItemRequestsPanel itemId={value.id} />}
    <CodesPanel type="ITEM" targetId={value.id} archived={value.archived} />
    <section className="panel table-panel" aria-labelledby="history-heading">
      <div className="panel-header"><div><h2 id="history-heading">History</h2><p>Every change to this item's stock, newest first.</p></div></div>
      <ErrorMessage error={history.error} retry={history.reload} />
      {!history.data && !history.error && <Loading variant="list" label="Loading history" />}
      {history.data?.items.length === 0 && <p className="panel-body muted">No stock has been recorded yet.</p>}
      {history.data && <MovementList movements={history.data.items} unit={value.unit} canReverse={editable} reverse={setReversing} />}
      {history.data && <Pager data={history.data} onPage={setHistoryPage} />}
    </section>
    {requesting && <NewRequestDialog item={{ id: value.id, name: value.name, unit: value.unit }} locationId={requesting.locationId} close={() => setRequesting(undefined)}
      done={request => navigate(root + '/requests/' + request.id)} />}
    {sheet && <SlotSheet slot={sheet} item={value} canManage={editable} close={() => setSheet(undefined)} request={() => { const slot = sheet; setSheet(undefined); setRequesting({ locationId: slot.locationId }); }}
      pick={kind => { setSheet(undefined); setAction({ kind, locationId: sheet.locationId }); }}
      remove={() => { const slot = sheet; setSheet(undefined); void mutate(base + '/items/' + itemId + '/placements/' + slot.locationId, 'DELETE', undefined, locationPath(slot) + ' removed'); }} />}
    {action && <StockDialog action={action.kind} item={value} base={base} balances={slots} locationId={action.locationId} close={() => setAction(undefined)} done={finished} />}
    {reversing && <ReverseDialog movement={reversing} unit={value.unit} base={base} close={() => setReversing(undefined)} done={finished} />}
    {editing && <Dialog title="Edit item" close={() => setEditing(false)}><ItemForm key={value.version} item={value} unitLocked={unitLocked} cancel={() => setEditing(false)} saved={() => { setEditing(false); item.reload(); }} /><p className="dialog-footer-link"><button type="button" className="text-button" onClick={item.reload}>Reload latest version</button></p></Dialog>}
    {archiving && <Dialog title={'Archive ' + value.name + '?'} close={() => setArchiving(false)}>
      <p>{hasStock ? 'This item still has stock. Use, move or count it to zero first.' : 'It will be hidden from the inventory list. You can still find it with Include archived, and its history stays available.'}</p>
      <ErrorMessage error={error} />
      <div className="form-actions"><button type="button" className="button secondary" data-autofocus onClick={() => setArchiving(false)}>Cancel</button><button type="button" className="button danger" disabled={pending || hasStock} onClick={() => void mutate(base + '/items/' + itemId + '/archive', 'POST', { expectedVersion: value.version }).then(success => { if (success) navigate(root + '/items'); })}>{pending ? 'Archiving…' : 'Archive'}</button></div>
    </Dialog>}
  </>;
}
