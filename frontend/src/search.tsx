import { useId } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { query } from './api';
import { Badge, Empty, ErrorMessage, Loading, PageHeader, useResource } from './components';
import { Icon } from './icons';
import { locationPath } from './stock';
import type { SearchResults } from './types';
import { unitNames } from './types';
import { useWorkspace } from './workspace';

/** A search field that opens the results page; used on Home and in the desktop sidebar. */
export function SearchForm({ root, initial = '', compact = false }: { root: string; initial?: string; compact?: boolean }) {
  const navigate = useNavigate();
  const id = useId();
  return <form className={'search-form' + (compact ? ' compact-search' : '')} role="search" aria-label="Search this business"
    onSubmit={event => { event.preventDefault(); const q = String(new FormData(event.currentTarget).get('q') ?? '').trim(); if (q) navigate(root + '/search?q=' + encodeURIComponent(q)); }}>
    <div className="search-input"><Icon name="search" /><label htmlFor={id} className="sr-only">Search items, locations and codes</label>
      <input id={id} name="q" type="search" placeholder={compact ? 'Search' : 'Search items, SKUs, locations or a code'} defaultValue={initial} key={initial} autoComplete="off" enterKeyHint="search" /></div>
    {!compact && <button className="button secondary">Search</button>}
  </form>;
}

export function SearchPage() {
  const { base, organization } = useWorkspace();
  const root = '/app/organizations/' + organization.id;
  const [params] = useSearchParams();
  const q = (params.get('q') ?? '').trim();
  const results = useResource<SearchResults>(q ? query(base + '/search', { q }) : null);
  const data = results.data;
  const nothing = data && !data.code && !data.items.length && !data.locations.length;
  return <>
    <PageHeader title="Search" description="Items by name or SKU, locations by name, or a scanned code." />
    <SearchForm root={root} initial={q} />
    <ErrorMessage error={results.error} retry={results.reload} />
    {q && !data && !results.error && <div className="panel table-panel"><Loading variant="list" label="Searching" /></div>}
    {!q && <Empty icon="search" title="Search this business">Type part of an item name, a SKU, a location, or paste a label code.</Empty>}
    {nothing && <Empty icon="search" title={'Nothing matches “' + q + '”'}>Check the spelling, try fewer letters, or search by SKU.</Empty>}
    {data?.code && <section className="panel code-match" aria-labelledby="code-heading">
      <h2 id="code-heading" className="section-heading">Code match</h2>
      <Link className="code-match-link" to={root + (data.code.type === 'ITEM' ? '/items/' : '/locations/') + data.code.id}>
        <Icon name={data.code.type === 'ITEM' ? 'catalog' : 'location'} />
        <span><strong>{data.code.parentName ? data.code.parentName + ' › ' + data.code.name : data.code.name}</strong>
          <span className="cell-secondary">{data.code.type === 'ITEM' ? 'Item' : 'Location'} with this label or barcode</span></span>
        <Icon name="chevronRight" />
      </Link>
    </section>}
    {!!data?.items.length && <section className="panel table-panel" aria-labelledby="item-results">
      <div className="panel-header"><div><h2 id="item-results">Items</h2><p>{data.items.length === 8 ? 'First 8 matches. Refine the search to see others.' : data.items.length + (data.items.length === 1 ? ' match' : ' matches')}</p></div></div>
      <ul className="record-list home-list">{data.items.map(item => <li key={item.id}>
        <div className="home-list-main"><Link className="record-link" to={root + '/items/' + item.id}>{item.name}</Link>
          <span className="cell-secondary">{item.sku ? <span className="mono">{item.sku}</span> : 'No SKU'} · {unitNames[item.unit]}</span></div>
        <Icon name="chevronRight" />
      </li>)}</ul>
    </section>}
    {!!data?.locations.length && <section className="panel table-panel" aria-labelledby="location-results">
      <div className="panel-header"><div><h2 id="location-results">Locations</h2><p>{data.locations.length === 8 ? 'First 8 matches.' : data.locations.length + (data.locations.length === 1 ? ' match' : ' matches')}</p></div></div>
      <ul className="record-list home-list">{data.locations.map(location => <li key={location.id}>
        <div className="home-list-main"><Link className="record-link" to={root + '/locations/' + location.id}>{locationPath({ locationName: location.name, parentName: location.parentName })}</Link>
          <span className="cell-secondary"><Badge>{location.type.charAt(0) + location.type.slice(1).toLowerCase()}</Badge></span></div>
        <Icon name="chevronRight" />
      </li>)}</ul>
    </section>}
  </>;
}
