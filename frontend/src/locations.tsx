import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { query, send } from './api';
import { Badge, Dialog, Empty, ErrorMessage, Field, Loading, PageHeader, Pager, useAll, useResource } from './components';
import { Icon } from './icons';
import type { Item, LocationType, Page, StorageLocation } from './types';
import { useWorkspace } from './workspace';
import { formatQuantity } from './stock';

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
  async function archive() {
    setPending(true); setError(undefined);
    try { await send(base + '/locations/' + locationId + '/archive', 'POST', { expectedVersion: current.data!.version }); navigate(root + '/locations'); }
    catch (value) { setError(value); } finally { setPending(false); }
  }
  if (current.error) return <ErrorMessage error={current.error} retry={current.reload} />;
  if (locationId && !current.data) return <Loading />;
  const value = current.data;
  const editable = canEdit && !value?.archived;
  const addButton = <button type="button" className="button primary" onClick={() => setDialog('create')}><Icon name="plus" />{value ? 'Add child location' : 'Add location'}</button>;
  return <>
    <PageHeader
      back={value ? { to: root + '/locations' + (value.parentId ? '/' + value.parentId : ''), label: value.parentId ? 'Parent location' : 'All locations' } : undefined}
      title={value?.name ?? 'Locations'}
      description={value ? undefined : 'Rooms, shelves, cabinets and bins where items are kept.'}>
      {editable && <>{addButton}{value && <><button type="button" className="button secondary" onClick={() => setDialog('edit')}>Edit location</button><button type="button" className="button secondary" onClick={() => setDialog('archive')}>Archive location</button></>}</>}
    </PageHeader>
    {value && <p className="location-meta"><Badge>{typeNames[value.type]}</Badge>{value.archived && <Badge tone="warning">Archived</Badge>}</p>}

    <section aria-labelledby="child-locations">
      {value && <h2 className="section-heading" id="child-locations">Inside {value.name}</h2>}
      {!value && <h2 className="sr-only" id="child-locations">All locations</h2>}
      <div className="toolbar">
        <form className="search-form" role="search" onSubmit={event => { event.preventDefault(); setQ(String(new FormData(event.currentTarget).get('search')).trim()); setPage(0); }}>
          <div className="search-input"><Icon name="search" /><input name="search" aria-label="Search locations" type="search" placeholder="Search locations" defaultValue={q} key={q} /></div>
          <button className="button secondary">Search</button>
        </form>
        <label className="checkbox"><input type="checkbox" checked={archived} onChange={event => { setArchived(event.target.checked); setPage(0); }} /> Include archived</label>
      </div>
      <ErrorMessage error={list.error} retry={list.reload} />
      {!list.data && !list.error && <div className="panel table-panel"><Loading variant="list" label="Loading locations" /></div>}
      {list.data && (list.data.items.length ? <div className="panel table-panel">
        <table className="data-table">
          <thead><tr><th scope="col">Location</th><th scope="col">Type</th><th scope="col"><span className="sr-only">Open</span></th></tr></thead>
          <tbody>{list.data.items.map(location => <tr key={location.id} className="linked">
            <td><span className="name-line"><Link className="record-link" to={root + '/locations/' + location.id}>{location.name}</Link>{location.archived && <Badge>Archived</Badge>}</span><span className="cell-secondary mobile-only">{typeNames[location.type]}</span></td>
            <td className="desktop-only">{typeNames[location.type]}</td>
            <td className="cell-chevron"><Icon name="chevronRight" /></td>
          </tr>)}</tbody>
        </table>
        <Pager data={list.data} onPage={setPage} />
      </div> : <Empty icon={q ? 'search' : 'location'}
        title={q ? 'No locations match “' + q + '”' : value ? 'Nothing inside ' + value.name : 'No locations yet'}
        action={q ? <button type="button" className="button secondary" onClick={() => { setQ(''); setPage(0); }}>Clear search</button> : undefined}>
        {q ? 'Check the spelling and try again.' : value ? 'Add a shelf, cabinet or bin here, or place items directly in this location.' : canEdit ? 'Use Add location to start with a room such as Stockroom, then add shelves or bins inside it.' : 'Locations your team adds will appear here.'}
      </Empty>)}
    </section>

    {value && <section className="panel table-panel" aria-labelledby="expected-items">
      <div className="panel-header"><div><h2 id="expected-items">Expected items</h2><p>Items placed directly in this location.</p></div></div>
      <ErrorMessage error={contents.error} retry={contents.reload} />
      {!contents.data && !contents.error && <Loading variant="list" label="Loading items" />}
      {contents.data?.items.length === 0 && <p className="panel-body muted">No items are placed here yet.</p>}
      {!!contents.data?.items.length && <table className="data-table">
        <thead><tr><th scope="col">Item</th><th scope="col">SKU</th><th scope="col" className="cell-number">On hand here</th><th scope="col"><span className="sr-only">Open</span></th></tr></thead>
        <tbody>{contents.data.items.map(item => <tr key={item.id} className="linked">
          <td><Link className="record-link" to={root + '/items/' + item.id}>{item.name}</Link><span className="cell-secondary mobile-only">{item.sku ? <span className="mono">{item.sku}</span> : 'No SKU'} · <span className="numeric">{formatQuantity(item.onHand, item.unit)}</span> here</span></td>
          <td className="desktop-only mono">{item.sku ?? <><span aria-hidden="true">—</span><span className="sr-only">No SKU</span></>}</td>
          <td className="desktop-only cell-number numeric">{formatQuantity(item.onHand, item.unit)}</td>
          <td className="cell-chevron"><Icon name="chevronRight" /></td>
        </tr>)}</tbody>
      </table>}
      {contents.data && <Pager data={contents.data} onPage={setContentsPage} />}
    </section>}

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
