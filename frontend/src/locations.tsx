import { RecordLink, useRecordNavigation } from './navigation';
import { useState } from 'react';
import type { FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router';
import { api, query, send } from './api';
import { Badge, Dialog, Empty, ErrorMessage, Field, Loading, PageHeader, Pager, SuccessMessage, useAll, useResource } from './components';
import { Icon } from './icons';
import type { Item, LocationType, Page, StorageLocation } from './types';
import { useWorkspace } from './workspace';
import { SlotSheet, StockDialog, StockRowActions, formatQuantity } from './stock';
import type { StockAction } from './stock';
import { CodesPanel } from './codes';
import { NewRequestDialog } from './requests';
import { SelectionBar, useLabelSelection } from './labels';
import { BrowseCard, Breadcrumbs, ViewToggle, VisualTile, spaceFacts, spaceIcons, unitIcons, useBrowseView } from './browse';
import { ItemForm, itemPhoto } from './catalog';
import type { StockBalance } from './types';

const typeNames: Record<LocationType, string> = { ROOM: 'Room', SHELF: 'Shelf', CABINET: 'Cabinet', BIN: 'Bin' };

function LocationForm({ location, parentId, saved, cancel }: { location?: StorageLocation; parentId?: string; saved: (location: StorageLocation) => void; cancel: () => void }) {
  const { base } = useWorkspace();
  const locations = useAll<StorageLocation>(base + '/locations');
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  const forbidden = new Set<string>();
  if (location) {
    forbidden.add(location.id);
    let changed = true;
    while (changed) {
      changed = false;
      for (const candidate of locations.data ?? []) if (candidate.parentId && forbidden.has(candidate.parentId) && !forbidden.has(candidate.id)) {
        forbidden.add(candidate.id); changed = true;
      }
    }
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setPending(true); setError(undefined);
    const form = new FormData(event.currentTarget);
    try {
      const result = await send<StorageLocation>(base + '/locations' + (location ? '/' + location.id : ''), location ? 'PATCH' : 'POST', {
        name: form.get('name'), type: form.get('type'), parentId: form.get('parentId') || null,
        ...(location ? { expectedVersion: location.version } : {}),
      });
      saved(result);
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  return <form onSubmit={submit}>
    <ErrorMessage error={error ?? locations.error} />
    <Field label="Location name"><input name="name" defaultValue={location?.name} maxLength={120} required /></Field>
    <Field label="Location type"><select name="type" defaultValue={location?.type ?? (parentId ? 'SHELF' : 'ROOM')}>{Object.entries(typeNames).map(([value, name]) => <option key={value} value={value}>{name}</option>)}</select></Field>
    {/* Remount once the list loads so the default parent can be selected; otherwise a child is saved at the top level. */}
    <Field label="Inside"><select key={locations.data ? 'ready' : 'loading'} name="parentId" defaultValue={location?.parentId ?? parentId ?? ''} disabled={!locations.data}><option value="">Nothing — top level</option>{locations.data?.filter(candidate => !forbidden.has(candidate.id)).map(candidate => <option value={candidate.id} key={candidate.id}>{candidate.name}</option>)}</select></Field>
    <div className="form-actions"><button type="button" className="button secondary" onClick={cancel}>Cancel</button><button className="button primary" disabled={pending || !locations.data}>{pending ? 'Saving…' : location ? 'Save location' : 'Create location'}</button></div>
  </form>;
}

export function LocationsPage() {
  const nav = useRecordNavigation();
  const { locationId } = useParams();
  const { base, canEdit, organization } = useWorkspace();
  const navigate = useNavigate();
  const current = useResource<StorageLocation>(locationId ? base + '/locations/' + locationId : null);
  const [page, setPage] = useState(0);
  const [q, setQ] = useState('');
  const [archived, setArchived] = useState(false);
  const list = useResource<Page<StorageLocation>>(query(base + '/locations', {
    parentId: locationId, rootsOnly: !locationId && !q, q, page, includeArchived: archived,
  }));
  const [contentsPage, setContentsPage] = useState(0);
  const contents = useResource<Page<Item>>(locationId ? query(base + '/locations/' + locationId + '/items', { page: contentsPage }) : null);
  const [dialog, setDialog] = useState<'create' | 'edit' | 'archive' | null>(null);
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  const root = '/app/organizations/' + organization.id;
  const selection = useLabelSelection(!locationId && canEdit && new URLSearchParams(nav.current.search).get('select') === 'labels');
  const [slot, setSlot] = useState<{ item: Item; balances: StockBalance[]; balance: StockBalance }>();
  const [stockAction, setStockAction] = useState<StockAction>();
  const [stockStatus, setStockStatus] = useState('');
  const [requesting, setRequesting] = useState<Item>();
  const [adding, setAdding] = useState(false);
  const [view, setView] = useBrowseView('locations');
  async function openSlot(item: Item, action?: 'usage' | 'receive') {
    setError(undefined); setStockStatus('');
    try {
      const balances = await api<StockBalance[]>(base + '/inventory/balances?itemId=' + item.id);
      const balance = balances.find(entry => entry.locationId === locationId);
      if (!balance) { contents.reload(); throw new Error('This placement changed. Check the shelf and try again.'); }
      setSlot({ item, balances, balance }); setStockAction(action);
    } catch (value) { setError(value); }
  }
  async function archive() {
    setPending(true); setError(undefined);
    try { await send(base + '/locations/' + locationId + '/archive', 'POST', { expectedVersion: current.data!.version }); navigate(root + '/locations'); }
    catch (value) { setError(value); } finally { setPending(false); }
  }
  if (current.error) return <ErrorMessage error={current.error} retry={current.reload} />;
  if (locationId && !current.data) return <Loading />;
  const value = current.data;
  const editable = canEdit && !value?.archived;
  // A location with no active children holds items directly; only those can take a new item.
  const leaf = Boolean(value && !value.archived && !q && !archived && list.data?.totalElements === 0);
  // Where items can go, adding an item is the main job; elsewhere it is adding storage.
  const addButton = <button type="button" className={'button ' + (leaf ? 'secondary' : 'primary')} onClick={() => setDialog('create')}><Icon name="plus" />{value ? 'Add child location' : 'Add location'}</button>;
  const showContentsFirst = value?.type === 'SHELF' || value?.type === 'BIN';
  const locationChooser = (location: StorageLocation) => <label className="select-line"><input type="checkbox" checked={selection.selected.includes(location.id)}
    disabled={location.archived} onChange={() => selection.toggle(location.id)} /><span className="record-link">{location.name}</span></label>;
  const expectedItems = value && (
    <section className="panel table-panel" aria-labelledby="expected-items">
      <div className="panel-header"><div><h2 id="expected-items">Expected items</h2><p>Items placed directly in this location.</p></div></div>
      <ErrorMessage error={contents.error} retry={contents.reload} />
      {!contents.data && !contents.error && <Loading variant="list" label="Loading items" />}
      {contents.data?.items.length === 0 && <p className="panel-body muted">No items are placed here yet.</p>}
      {!!contents.data?.items.length && view === 'grid' && <ul className="browse-grid" aria-label="Expected items">{contents.data.items.map(item => <BrowseCard key={item.id}
        muted={item.archived} visual={<VisualTile name={item.name} icon={unitIcons[item.unit]} photo={itemPhoto(base, item)} />}
        title={<RecordLink from={value.name} className="record-link" to={root + '/items/' + item.id}>{item.name}</RecordLink>}
        meta={<span><span className="numeric quantity-strong">{formatQuantity(item.onHand, item.unit)}</span> here</span>}
        badges={item.archived ? <Badge>Archived</Badge> : undefined}
        actions={value.archived || item.archived ? undefined : <StockRowActions quantity={item.onHand} subject={item.name} open={kind => void openSlot(item, kind)} />} />)}</ul>}
      {!!contents.data?.items.length && view === 'list' && <table className="data-table">
        <thead><tr><th scope="col">Item</th><th scope="col">SKU</th><th scope="col" className="cell-number">On hand here</th><th scope="col"><span className="sr-only">Actions</span></th></tr></thead>
        <tbody>{contents.data.items.map(item => <tr key={item.id} className="linked">
          <td><span className="tile-cell"><VisualTile size="sm" name={item.name} icon={unitIcons[item.unit]} photo={itemPhoto(base, item)} /><span><RecordLink from={value.name} className="record-link" to={root + '/items/' + item.id}>{item.name}</RecordLink><span className="cell-secondary mobile-only">{item.sku ? <span className="mono">{item.sku}</span> : 'No SKU'} · <span className="numeric">{formatQuantity(item.onHand, item.unit)}</span> here</span></span></span></td>
          <td className="desktop-only mono">{item.sku ?? <><span aria-hidden="true">—</span><span className="sr-only">No SKU</span></>}</td>
          <td className="desktop-only cell-number numeric">{formatQuantity(item.onHand, item.unit)}</td>
          <td className="cell-actions stock-actions-cell">{value.archived || item.archived ? <Icon name="chevronRight" /> :
            <StockRowActions quantity={item.onHand} subject={item.name} open={kind => void openSlot(item, kind)} />}</td>
        </tr>)}</tbody>
      </table>}
      {contents.data && <Pager data={contents.data} onPage={setContentsPage} />}
    </section>
  );
  return <>
    <PageHeader
      back={value && nav.hasOrigin ? nav.back({ to: root + '/locations', label: 'All locations' }) : undefined}
      trail={value && <Breadcrumbs trail={[{ label: 'All locations', to: root + '/locations' },
        ...(value.ancestors ?? []).map(step => ({ label: step.name, to: root + '/locations/' + step.id })), { label: value.name }]} />}
      title={value?.name ?? 'Locations'}
      description={value ? undefined : 'Rooms, shelves, cabinets and bins where items are kept.'}>
      {canEdit && !value && <button type="button" className="button secondary" aria-pressed={selection.selecting} onClick={selection.toggleMode}><Icon name="label" />{selection.selecting ? 'Cancel' : 'Print labels'}</button>}
      {editable && leaf && <button type="button" className="button primary" onClick={() => setAdding(true)}><Icon name="plus" />Add item here</button>}
      {editable && <>{addButton}{value && <><button type="button" className="button secondary" onClick={() => setDialog('edit')}>Edit location</button><button type="button" className="button secondary" onClick={() => setDialog('archive')}>Archive location</button></>}</>}
    </PageHeader>
    {value && <div className="location-meta"><span className="location-badges"><Badge>{typeNames[value.type]}</Badge>{value.archived && <Badge tone="warning">Archived</Badge>}</span>
      <ViewToggle view={view} onChange={setView} label="Layout" /></div>}

    {selection.selecting && <SelectionBar count={selection.selected.length} noun="location" href={root + '/labels?locations=' + selection.selected.join(',')} />}
    {stockStatus && <SuccessMessage>{stockStatus}</SuccessMessage>}
    {showContentsFirst && expectedItems}
    {/* Where nothing is stored inside (most shelves and bins), one quiet line replaces the search and empty state. */}
    {leaf ? <p className="muted quiet-empty">No storage inside {value!.name}.</p> : <section aria-labelledby="child-locations">
      {value && <h2 className="section-heading" id="child-locations">Storage inside {value.name}</h2>}
      {!value && <h2 className="sr-only" id="child-locations">All locations</h2>}
      <div className="toolbar">
        <form className="search-form" role="search" onSubmit={event => { event.preventDefault(); setQ(String(new FormData(event.currentTarget).get('search')).trim()); setPage(0); }}>
          <div className="search-input"><Icon name="search" /><input name="search" aria-label="Search locations" type="search" placeholder="Search locations" defaultValue={q} key={q} /></div>
          <button className="button secondary">Search</button>
        </form>
        <label className="checkbox"><input type="checkbox" checked={archived} onChange={event => { setArchived(event.target.checked); setPage(0); }} /> Include archived</label>
        {!value && <ViewToggle view={view} onChange={setView} label="Locations layout" />}
      </div>
      <ErrorMessage error={list.error} retry={list.reload} />
      {!list.data && !list.error && <div className="panel table-panel"><Loading variant="list" label="Loading locations" /></div>}
      {list.data && (list.data.items.length ? <div className="panel table-panel">
        {view === 'grid' ? <ul className="browse-grid space-grid" aria-label={value ? 'Storage inside ' + value.name : 'Locations'}>{list.data.items.map(location => <BrowseCard key={location.id}
          selected={selection.selecting && selection.selected.includes(location.id)} muted={location.archived}
          visual={<VisualTile name={location.name} icon={spaceIcons[location.type]} />}
          title={selection.selecting ? locationChooser(location) : <RecordLink from={value?.name ?? 'Locations'} className="record-link" to={root + '/locations/' + location.id}>{location.name}</RecordLink>}
          meta={<><span>{typeNames[location.type]}</span><span>{spaceFacts(location)}</span></>}
          badges={location.archived ? <Badge>Archived</Badge> : undefined} />)}</ul> : <table className="data-table">
          <thead><tr><th scope="col">Location</th><th scope="col">Type</th><th scope="col">Contents</th><th scope="col"><span className="sr-only">Open</span></th></tr></thead>
          <tbody>{list.data.items.map(location => <tr key={location.id} className={selection.selecting ? 'selectable' : 'linked'}>
            <td><span className="tile-cell"><VisualTile size="sm" name={location.name} icon={spaceIcons[location.type]} /><span><span className="name-line">{selection.selecting
              ? locationChooser(location)
              : <RecordLink from={value?.name ?? 'Locations'} className="record-link" to={root + '/locations/' + location.id}>{location.name}</RecordLink>}{location.archived && <Badge>Archived</Badge>}</span><span className="cell-secondary mobile-only">{typeNames[location.type]} · {spaceFacts(location)}</span></span></span></td>
            <td className="desktop-only">{typeNames[location.type]}</td>
            <td className="desktop-only cell-secondary-text">{spaceFacts(location)}</td>
            <td className="cell-chevron"><Icon name="chevronRight" /></td>
          </tr>)}</tbody>
        </table>}
        <Pager data={list.data} onPage={setPage} />
      </div> : value && !q ? <p className="muted quiet-empty">No storage inside {value.name}.</p> : <Empty icon={q ? 'search' : 'location'}
        title={q ? 'No locations match “' + q + '”' : value ? 'No child locations' : 'No locations yet'}
        action={q ? <button type="button" className="button secondary" onClick={() => { setQ(''); setPage(0); }}>Clear search</button> : undefined}>
        {q ? 'Check the spelling and try again.' : value ? 'There are no nested storage locations here.' : canEdit ? 'Use Add location to start with a room such as Stockroom, then add shelves or bins inside it.' : 'Locations your team adds will appear here.'}
      </Empty>)}
    </section>}

    {!showContentsFirst && expectedItems}

    {value && <CodesPanel type="LOCATION" targetId={value.id} archived={value.archived} />}
    {slot && !stockAction && <SlotSheet slot={slot.balance} item={slot.item} canManage={canEdit} close={() => setSlot(undefined)} pick={setStockAction}
      request={() => { const chosen = slot.item; setSlot(undefined); setRequesting(chosen); }} />}
    {requesting && value && <NewRequestDialog item={{ id: requesting.id, name: requesting.name, unit: requesting.unit }} locationId={value.id}
      close={() => setRequesting(undefined)} done={request => navigate(root + '/requests/' + request.id)} />}
    {slot && stockAction && <StockDialog action={stockAction} item={slot.item} base={base} balances={slot.balances} locationId={slot.balance.locationId}
      close={() => { setStockAction(undefined); setSlot(undefined); }}
      done={message => { setStockAction(undefined); setSlot(undefined); setStockStatus(message); contents.reload(); }} />}
    {adding && value && <Dialog title={'Add item to ' + value.name} close={() => setAdding(false)}>
      <ItemForm cancel={() => setAdding(false)} saved={item => void (async () => {
        try {
          await send(base + '/items/' + item.id + '/placements/' + value.id, 'PUT');
          navigate(root + '/items/' + item.id + '?action=receive&location=' + value.id);
        } catch { navigate(root + '/items/' + item.id); }
      })()} />
    </Dialog>}
    {(dialog === 'create' || dialog === 'edit') && <Dialog title={dialog === 'edit' ? 'Edit location' : value ? 'Add location inside ' + value.name : 'Add location'} close={() => setDialog(null)}>
      <LocationForm location={dialog === 'edit' ? value : undefined} parentId={dialog === 'create' ? locationId : undefined} cancel={() => setDialog(null)} saved={location => { setDialog(null); list.reload(); current.reload(); navigate(root + '/locations/' + location.id); }} />
      {dialog === 'edit' && <p className="dialog-footer-link"><button type="button" className="text-button" onClick={current.reload}>Reload latest version</button></p>}
    </Dialog>}
    {dialog === 'archive' && <Dialog title={'Archive ' + value?.name + '?'} close={() => setDialog(null)}>
      <p>Move or remove any active locations and items inside it first. Its history stays available.</p>
      <ErrorMessage error={error} />
      <div className="form-actions"><button type="button" className="button secondary" data-autofocus onClick={() => setDialog(null)}>Cancel</button><button type="button" className="button danger" disabled={pending} onClick={() => void archive()}>{pending ? 'Archiving…' : 'Archive'}</button></div>
    </Dialog>}
  </>;
}
