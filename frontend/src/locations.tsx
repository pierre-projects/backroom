import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { query, send } from './api';
import { Badge, Dialog, Empty, ErrorMessage, Field, Loading, PageHeader, Pager, useAll, useResource } from './components';
import type { Item, Page, StorageLocation } from './types';
import { useWorkspace } from './workspace';

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
  return <form onSubmit={submit}><ErrorMessage error={error ?? locations.error} /><Field label="Location name"><input name="name" defaultValue={location?.name} maxLength={120} required /></Field><Field label="Location type"><select name="type" defaultValue={location?.type ?? (parentId ? 'SHELF' : 'ROOM')}><option value="ROOM">Room</option><option value="SHELF">Shelf</option><option value="CABINET">Cabinet</option><option value="BIN">Bin</option></select></Field><Field label="Parent location"><select name="parentId" defaultValue={location?.parentId ?? parentId ?? ''} disabled={!locations.data}><option value="">No parent — top level</option>{locations.data?.filter(candidate => !forbidden.has(candidate.id)).map(candidate => <option value={candidate.id} key={candidate.id}>{candidate.name}</option>)}</select></Field><div className="form-actions"><button type="button" className="button secondary" onClick={cancel}>Cancel</button><button className="button primary" disabled={pending || !locations.data}>{pending ? 'Saving…' : location ? 'Save location' : 'Create location'}</button></div></form>;
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
  return <>{value && <Link className="back-link" to={root + '/locations' + (value.parentId ? '/' + value.parentId : '')}>← {value.parentId ? 'Parent location' : 'All locations'}</Link>}
    <PageHeader eyebrow={value ? 'STORAGE LOCATION' : 'A HOME FOR EVERYTHING'} title={value?.name ?? 'Locations'} description={value ? 'Child locations and the items expected here.' : 'Organize your rooms, shelves, cabinets, and bins.'}>
      {canEdit && !value?.archived && <><button className="button primary" onClick={() => setDialog('create')}>{value ? '+ Add child location' : '+ Add location'}</button>{value && <><button className="button secondary" onClick={() => setDialog('edit')}>Edit location</button><button className="button secondary" onClick={() => setDialog('archive')}>Archive location</button></>}</>}
    </PageHeader>
    {value && <p className="location-status"><Badge>{value.type.toLowerCase()}</Badge> <Badge>{value.archived ? 'Archived' : 'Active'}</Badge></p>}
    <div className="toolbar"><form className="search-form" onSubmit={event => { event.preventDefault(); setQ(String(new FormData(event.currentTarget).get('search'))); setPage(0); }}><input name="search" aria-label="Search locations" type="search" placeholder="Search locations" /><button className="button secondary">Search</button></form><label className="checkbox"><input type="checkbox" checked={archived} onChange={event => { setArchived(event.target.checked); setPage(0); }} /> Include archived</label></div>
    <ErrorMessage error={list.error} retry={list.reload} />{!list.data && !list.error && <Loading />}
    {list.data && (list.data.items.length ? <div className="location-grid">{list.data.items.map(location => <Link className="location-card panel" key={location.id} to={root + '/locations/' + location.id}><span className="location-symbol" aria-hidden="true">▦</span><Badge>{location.type.toLowerCase()}</Badge><h2>{location.name}</h2><span className="muted">{location.archived ? 'Archived' : 'View location →'}</span></Link>)}</div> : <Empty title={value ? 'No child locations' : q ? 'No matching locations' : 'Give your stock a home'}>{value ? 'This location can hold expected item placements when active.' : q ? 'Try another location name.' : canEdit ? 'Start with a stockroom, then add shelves or bins as needed.' : 'Your team has not created any locations yet.'}</Empty>)}
    {list.data && <Pager data={list.data} onPage={setPage} />}
    {value && <section className="panel"><h2>Expected items</h2><ErrorMessage error={contents.error} retry={contents.reload} />{!contents.data && !contents.error && <Loading />}{contents.data?.items.length === 0 && <p className="muted">No items are assigned directly to this location.</p>}<ul className="record-list">{contents.data?.items.map(item => <li key={item.id}><Link to={root + '/items/' + item.id}>{item.name}</Link><span className="mono muted">{item.sku}</span></li>)}</ul>{contents.data && <Pager data={contents.data} onPage={setContentsPage} />}</section>}
    {(dialog === 'create' || dialog === 'edit') && <Dialog title={dialog === 'edit' ? 'Edit location' : 'Add location'} close={() => setDialog(null)}><LocationForm location={dialog === 'edit' ? value : undefined} parentId={dialog === 'create' ? locationId : undefined} cancel={() => setDialog(null)} saved={location => { setDialog(null); list.reload(); current.reload(); navigate(root + '/locations/' + location.id); }} />{dialog === 'edit' && <button className="text-button" onClick={current.reload}>Reload current record</button>}</Dialog>}
    {dialog === 'archive' && <Dialog title="Archive location" close={() => setDialog(null)}><p>Archive <strong>{value?.name}</strong>? Active child locations and item placements must be removed or moved first.</p><ErrorMessage error={error} /><div className="form-actions"><button className="button secondary" onClick={() => setDialog(null)}>Cancel</button><button className="button primary" disabled={pending} onClick={() => void archive()}>Confirm archive</button></div></Dialog>}
  </>;
}
