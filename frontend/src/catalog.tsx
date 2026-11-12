import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { query, send } from './api';
import { Badge, Dialog, Empty, ErrorMessage, Field, Loading, PageHeader, Pager, SuccessMessage, useAll, useResource } from './components';
import { Icon } from './icons';
import type { Item, Page, Placement, StorageLocation } from './types';
import { unitNames } from './types';
import { useWorkspace } from './workspace';

function ItemForm({ item, saved, cancel }: { item?: Item; saved: (item: Item) => void; cancel: () => void }) {
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
    <Field label="Base unit"><select name="unit" defaultValue={item?.unit ?? 'EACH'}>{Object.entries(unitNames).map(([value, name]) => <option value={value} key={value}>{name}</option>)}</select><small>The unit you count this item in. Packs or cases can be separate items.</small></Field>
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
  return <>
    <PageHeader title="Catalog" description="Everything your business stocks, with SKU and unit.">{canEdit && addButton}</PageHeader>
    <div className="toolbar">
      <form className="search-form" role="search" onSubmit={event => { event.preventDefault(); setQ(String(new FormData(event.currentTarget).get('search')).trim()); setPage(0); }}>
        <div className="search-input"><Icon name="search" /><input name="search" type="search" aria-label="Search catalog" placeholder="Search by name or SKU" defaultValue={q} key={q} /></div>
        <button className="button secondary">Search</button>
      </form>
      <label className="checkbox"><input type="checkbox" checked={archived} onChange={event => { setArchived(event.target.checked); setPage(0); }} /> Include archived</label>
    </div>
    <ErrorMessage error={items.error} retry={items.reload} />
    {!items.data && !items.error && <div className="panel table-panel"><Loading variant="list" label="Loading items" /></div>}
    {items.data && (items.data.items.length ? <div className="panel table-panel">
      <table className="data-table">
        <thead><tr><th scope="col">Item</th><th scope="col">SKU</th><th scope="col">Base unit</th><th scope="col"><span className="sr-only">Open</span></th></tr></thead>
        <tbody>{items.data.items.map(item => <tr key={item.id} className="linked">
          <td><span className="name-line"><Link className="record-link" to={root + '/items/' + item.id}>{item.name}</Link>{item.archived && <Badge>Archived</Badge>}</span>
            <span className="cell-secondary mobile-only">{item.sku ? <span className="mono">{item.sku}</span> : 'No SKU'} · {unitNames[item.unit]}</span></td>
          <td className="desktop-only mono">{item.sku ?? <><span aria-hidden="true">—</span><span className="sr-only">No SKU</span></>}</td>
          <td className="desktop-only">{unitNames[item.unit]}</td>
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
  const [placementPage, setPlacementPage] = useState(0);
  const placements = useResource<Page<Placement>>(query(base + '/items/' + itemId + '/placements', { page: placementPage }));
  const leaves = useAll<StorageLocation>(base + '/locations?leafOnly=true');
  const allLocations = useAll<StorageLocation>(base + '/locations');
  // Parent names tell two "Shelf A" locations apart.
  const parentOf = (locationId: string) => {
    const location = allLocations.data?.find(candidate => candidate.id === locationId);
    return location?.parentId ? allLocations.data?.find(candidate => candidate.id === location.parentId)?.name : undefined;
  };
  const pathOf = (location: StorageLocation) => { const parent = parentOf(location.id); return parent ? parent + ' › ' + location.name : location.name; };
  const [editing, setEditing] = useState(false);
  const [archiving, setArchiving] = useState(false);
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  const [status, setStatus] = useState('');
  const root = '/app/organizations/' + organization.id;
  async function mutate(path: string, method: string, data?: unknown) {
    setPending(true); setError(undefined); setStatus('');
    try { await send(path, method, data); placements.reload(); leaves.reload(); return true; }
    catch (value) { setError(value); return false; }
    finally { setPending(false); }
  }
  async function addPlacement(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const locationId = String(new FormData(event.currentTarget).get('locationId'));
    const chosen = leaves.data?.find(location => location.id === locationId);
    const name = chosen && pathOf(chosen);
    if (await mutate(base + '/items/' + itemId + '/placements/' + locationId, 'PUT') && name) setStatus(name + ' added');
  }
  if (item.error) return <ErrorMessage error={item.error} retry={item.reload} />;
  if (!item.data) return <Loading />;
  const value = item.data;
  const editable = canEdit && !value.archived;
  return <>
    <PageHeader back={{ to: root + '/items', label: 'Catalog' }} title={value.name}>
      {editable && <><button type="button" className="button secondary" onClick={() => setEditing(true)}>Edit item</button><button type="button" className="button secondary" onClick={() => setArchiving(true)}>Archive item</button></>}
    </PageHeader>
    <dl className="detail-grid">
      <div><dt>SKU</dt><dd className="mono">{value.sku ?? 'Not set'}</dd></div>
      <div><dt>Base unit</dt><dd>{unitNames[value.unit]}</dd></div>
      {value.archived && <div><dt>Status</dt><dd><Badge tone="warning">Archived</Badge></dd></div>}
    </dl>
    <section className="panel" aria-labelledby="expected-locations">
      <h2 className="section-heading" id="expected-locations">Expected locations</h2>
      <p className="section-intro">Where your team should find this item.</p>
      <ErrorMessage error={error ?? placements.error ?? leaves.error} retry={() => { item.reload(); placements.reload(); leaves.reload(); }} />
      {status && <SuccessMessage>{status}</SuccessMessage>}
      {!placements.data && !placements.error && <Loading />}
      {placements.data?.items.length === 0 && <p className="muted">No locations yet.{editable ? ' Choose one below.' : ''}</p>}
      <ul className="record-list">{placements.data?.items.map(placement => <li key={placement.id}>
        <span className="record-main"><Icon name="location" /><span className="location-path">{parentOf(placement.locationId) && <span className="location-parent">{parentOf(placement.locationId)}<span aria-hidden="true"> ›</span></span>}<Link className="record-link" to={root + '/locations/' + placement.locationId}>{placement.locationName}</Link></span></span>
        {editable && <button type="button" className="text-button danger" disabled={pending} onClick={() => void mutate(base + '/items/' + itemId + '/placements/' + placement.locationId, 'DELETE')}>Remove<span className="sr-only"> {placement.locationName}</span></button>}
      </li>)}</ul>
      {placements.data && <Pager data={placements.data} onPage={setPlacementPage} />}
      {editable && <form className="inline-form" onSubmit={addPlacement}>
        <Field label="Storage location"><select name="locationId" required disabled={!leaves.data?.length || pending}><option value="">Choose a location</option>{leaves.data?.map(location => <option key={location.id} value={location.id}>{pathOf(location)}</option>)}</select></Field>
        <button className="button primary" disabled={pending || !leaves.data?.length}>{pending ? 'Adding…' : 'Add placement'}</button>
        {leaves.data?.length === 0 && <p className="field-help">Create a location with no locations inside it first, such as a shelf or bin.</p>}
      </form>}
    </section>
    {editing && <Dialog title="Edit item" close={() => setEditing(false)}><ItemForm key={value.version} item={value} cancel={() => setEditing(false)} saved={() => { setEditing(false); item.reload(); }} /><p className="dialog-footer-link"><button type="button" className="text-button" onClick={item.reload}>Reload latest version</button></p></Dialog>}
    {archiving && <Dialog title={'Archive ' + value.name + '?'} close={() => setArchiving(false)}>
      <p>Its expected locations are removed from active use. You can still find it with <strong>Include archived</strong>, and its history stays available.</p>
      <ErrorMessage error={error} />
      <div className="form-actions"><button type="button" className="button secondary" data-autofocus onClick={() => setArchiving(false)}>Cancel</button><button type="button" className="button danger" disabled={pending} onClick={() => void mutate(base + '/items/' + itemId + '/archive', 'POST', { expectedVersion: value.version }).then(success => { if (success) navigate(root + '/items'); })}>{pending ? 'Archiving…' : 'Archive'}</button></div>
    </Dialog>}
  </>;
}
