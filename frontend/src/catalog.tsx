import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { query, send } from './api';
import { Badge, Dialog, Empty, ErrorMessage, Field, Loading, PageHeader, Pager, useAll, useResource } from './components';
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
  return <form onSubmit={submit}><ErrorMessage error={error} /><Field label="Item name"><input name="name" defaultValue={item?.name} maxLength={160} required /></Field><Field label="SKU (optional)"><input name="sku" defaultValue={item?.sku ?? ''} maxLength={80} /><small>SKUs are saved in uppercase and remain reserved after archival.</small></Field><Field label="Base unit"><select name="unit" defaultValue={item?.unit ?? 'EACH'}>{Object.entries(unitNames).map(([value, name]) => <option value={value} key={value}>{name}</option>)}</select><small>Choose the unit you record this item in. Packages can be separate items.</small></Field><div className="form-actions"><button className="button secondary" type="button" onClick={cancel}>Cancel</button><button className="button primary" disabled={pending}>{pending ? 'Saving…' : item ? 'Save item' : 'Create item'}</button></div></form>;
}
export function CatalogPage() {
  const { base, canEdit, organization } = useWorkspace();
  const [page, setPage] = useState(0);
  const [q, setQ] = useState('');
  const [archived, setArchived] = useState(false);
  const [creating, setCreating] = useState(false);
  const navigate = useNavigate();
  const items = useResource<Page<Item>>(query(base + '/items', { page, q, includeArchived: archived }));
  return <><PageHeader eyebrow="KNOW WHAT YOU HAVE" title="Catalog" description="Products, supplies, and the places you keep them.">{canEdit && <button className="button primary" onClick={() => setCreating(true)}>+ Add item</button>}</PageHeader>
    <div className="toolbar"><form className="search-form" onSubmit={event => { event.preventDefault(); setQ(String(new FormData(event.currentTarget).get('search'))); setPage(0); }}><input name="search" type="search" aria-label="Search catalog" placeholder="Search by name or SKU" /><button className="button secondary">Search</button></form><label className="checkbox"><input type="checkbox" checked={archived} onChange={event => { setArchived(event.target.checked); setPage(0); }} /> Include archived</label></div>
    <ErrorMessage error={items.error} retry={items.reload} />{!items.data && !items.error && <Loading />}
    {items.data && (items.data.items.length ? <div className="panel table-panel"><table><thead><tr><th>Item</th><th>SKU</th><th>Unit</th><th>Status</th></tr></thead><tbody>{items.data.items.map(item => <tr key={item.id}><td><Link className="record-link" to={'/app/organizations/' + organization.id + '/items/' + item.id}>{item.name}</Link></td><td className="mono">{item.sku ?? '—'}</td><td>{unitNames[item.unit]}</td><td><Badge>{item.archived ? 'Archived' : 'Active'}</Badge></td></tr>)}</tbody></table></div> : <Empty title={q ? 'No matching items' : 'A clearer picture starts here'}>{q ? 'Try another name or SKU.' : canEdit ? 'Add your first item, choose its unit, and give it a home.' : 'Your team has not added any items yet.'}</Empty>)}
    {items.data && <Pager data={items.data} onPage={setPage} />}
    {creating && <Dialog title="Add item" close={() => setCreating(false)}><ItemForm cancel={() => setCreating(false)} saved={item => navigate('/app/organizations/' + organization.id + '/items/' + item.id)} /></Dialog>}
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
  const [editing, setEditing] = useState(false);
  const [archiving, setArchiving] = useState(false);
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  async function mutate(path: string, method: string, data?: unknown) {
    setPending(true); setError(undefined);
    try { await send(path, method, data); placements.reload(); leaves.reload(); return true; }
    catch (value) { setError(value); return false; }
    finally { setPending(false); }
  }
  async function addPlacement(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const locationId = new FormData(event.currentTarget).get('locationId');
    await mutate(base + '/items/' + itemId + '/placements/' + locationId, 'PUT');
  }
  if (item.error) return <ErrorMessage error={item.error} retry={item.reload} />;
  if (!item.data) return <Loading />;
  const value = item.data;
  return <><Link className="back-link" to="../items">← Catalog</Link><PageHeader eyebrow="ITEM DETAILS" title={value.name}>{canEdit && !value.archived && <><button className="button secondary" onClick={() => setEditing(true)}>Edit item</button><button className="button secondary" onClick={() => setArchiving(true)}>Archive item</button></>}</PageHeader>
    <div className="detail-strip"><div><span>SKU</span><strong className="mono">{value.sku ?? 'Not set'}</strong></div><div><span>Base unit</span><strong>{unitNames[value.unit]}</strong></div><div><span>Status</span><Badge>{value.archived ? 'Archived' : 'Active'}</Badge></div></div>
    <section className="panel"><h2>Expected locations</h2><p className="muted">Where your team expects to find this item.</p><ErrorMessage error={error ?? placements.error ?? leaves.error} retry={() => { item.reload(); placements.reload(); leaves.reload(); }} />
      {!placements.data && !placements.error && <Loading />}
      {placements.data?.items.length === 0 && <p className="muted">No locations assigned.</p>}
      <ul className="record-list">{placements.data?.items.map(placement => <li key={placement.id}><Link to={'/app/organizations/' + organization.id + '/locations/' + placement.locationId}>{placement.locationName}</Link>{canEdit && !value.archived && <button className="text-button danger" disabled={pending} onClick={() => void mutate(base + '/items/' + itemId + '/placements/' + placement.locationId, 'DELETE')}>Remove placement</button>}</li>)}</ul>
      {placements.data && <Pager data={placements.data} onPage={setPlacementPage} />}
      {canEdit && !value.archived && <form className="inline-form" onSubmit={addPlacement}><Field label="Storage location"><select name="locationId" required disabled={!leaves.data?.length}><option value="">Choose a leaf location</option>{leaves.data?.map(location => <option key={location.id} value={location.id}>{location.name}</option>)}</select></Field><button className="button primary" disabled={pending || !leaves.data?.length}>Add placement</button>{leaves.data?.length === 0 && <p className="field-help">Create a location without child locations first.</p>}</form>}
    </section>
    {editing && <Dialog title="Edit item" close={() => setEditing(false)}><ItemForm key={value.version} item={value} cancel={() => setEditing(false)} saved={() => { setEditing(false); item.reload(); }} /><button className="text-button" onClick={item.reload}>Reload current record</button></Dialog>}
    {archiving && <Dialog title="Archive item" close={() => setArchiving(false)}><p>Archive <strong>{value.name}</strong>? Its expected placements will be removed from active use. Its history will remain available.</p><ErrorMessage error={error} /><div className="form-actions"><button className="button secondary" onClick={() => setArchiving(false)}>Cancel</button><button className="button primary" disabled={pending} onClick={() => void mutate(base + '/items/' + itemId + '/archive', 'POST', { expectedVersion: value.version }).then(success => { if (success) navigate('/app/organizations/' + organization.id + '/items'); })}>Confirm archive</button></div></Dialog>}
  </>;
}
