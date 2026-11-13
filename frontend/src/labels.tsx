import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, download } from './api';
import { ErrorMessage, Loading, PageHeader, SuccessMessage, useResource } from './components';
import { Icon } from './icons';
import type { Item, LabelSettings, ResourceCode, StorageLocation, TargetType } from './types';
import { unitNames } from './types';
import { useWorkspace } from './workspace';

type Layout = 'A4_21' | 'LETTER_10' | 'SINGLE';
const layouts: { id: Layout; name: string; detail: string; perPage: number }[] = [
  { id: 'A4_21', name: 'A4 sheet, 21 labels', detail: '63.5 × 38.1 mm, same size as Avery L7160', perPage: 21 },
  { id: 'LETTER_10', name: 'US Letter sheet, 10 labels', detail: '4 × 2 in, same size as Avery 5163', perPage: 10 },
  { id: 'SINGLE', name: 'One label per page', detail: '63.5 × 38.1 mm, for label printers', perPage: 1 },
];
interface Preview { type: TargetType; id: string; name: string; detail: string; code: string | null }

/** Choose which labels to print, preview them, and download a print-ready PDF. Owners and Managers only. */
export function PrintLabelsPage() {
  const { base, canEdit, organization } = useWorkspace();
  const [params, setParams] = useSearchParams();
  const ids = (key: string) => (params.get(key) ?? '').split(',').filter(Boolean);
  const itemIds = ids('items'), locationIds = ids('locations');
  const settings = useResource<LabelSettings>(base + '/labels/settings');
  const [previews, setPreviews] = useState<Preview[]>();
  const [loadError, setLoadError] = useState<unknown>();
  const [layout, setLayout] = useState<Layout>(() => (navigator.language.endsWith('-US') || navigator.language.endsWith('-CA') ? 'LETTER_10' : 'A4_21'));
  const [start, setStart] = useState(1);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>();
  const [done, setDone] = useState('');
  const [revision, setRevision] = useState(0);
  const key = params.toString();

  useEffect(() => {
    let active = true;
    setPreviews(undefined); setLoadError(undefined);
    const activeQr = (codes: ResourceCode[]) => codes.find(code => code.kind === 'GENERATED' && code.status === 'ACTIVE')?.display ?? null;
    void Promise.all([
      ...itemIds.map(async id => {
        const [item, codes] = await Promise.all([api<Item>(base + '/items/' + id), api<ResourceCode[]>(base + '/codes?itemId=' + id)]);
        return { type: 'ITEM' as const, id, name: item.name, detail: [item.sku, unitNames[item.unit]].filter(Boolean).join(' · '), code: activeQr(codes) };
      }),
      ...locationIds.map(async id => {
        const [location, codes] = await Promise.all([api<StorageLocation>(base + '/locations/' + id), api<ResourceCode[]>(base + '/codes?locationId=' + id)]);
        const parent = location.parentId ? await api<StorageLocation>(base + '/locations/' + location.parentId) : null;
        return { type: 'LOCATION' as const, id, name: location.name, detail: parent ? 'in ' + parent.name : 'Location', code: activeQr(codes) };
      }),
    ]).then(list => { if (active) setPreviews(list); }).catch(value => { if (active) setLoadError(value); });
    return () => { active = false; };
  }, [base, key, revision]); // eslint-disable-line react-hooks/exhaustive-deps

  const selected = layouts.find(option => option.id === layout)!;
  const remove = (preview: Preview) => {
    const next = new URLSearchParams(params);
    const listKey = preview.type === 'ITEM' ? 'items' : 'locations';
    const remaining = (next.get(listKey) ?? '').split(',').filter(id => id && id !== preview.id);
    if (remaining.length) next.set(listKey, remaining.join(',')); else next.delete(listKey);
    setParams(next, { replace: true });
  };
  async function print() {
    if (!previews?.length) return;
    setPending(true); setError(undefined); setDone('');
    try {
      await download(base + '/labels.pdf', 'backroom-labels.pdf', {
        targets: previews.map(preview => ({ type: preview.type, id: preview.id })), layout, startPosition: layout === 'SINGLE' ? 1 : start,
      });
      try { localStorage.setItem(labelDownloadKey(organization.id), '1'); } catch { /* Downloads also work without browser storage. */ }
      setDone('Saved backroom-labels.pdf. Print it at 100% (Actual size).');
      setRevision(value => value + 1);
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  async function calibration() {
    setError(undefined);
    try { await download(base + '/labels/calibration.pdf?layout=' + (layout === 'SINGLE' ? 'A4_21' : layout), 'backroom-calibration.pdf'); }
    catch (value) { setError(value); }
  }

  if (!canEdit) return <ErrorMessage error={new Error('Only Owners and Managers can print labels.')} />;
  return <>
    <PageHeader title="Print labels" description="QR labels that open an item or location when scanned." />
    {settings.data?.localAddress && <div className="notice warning-message" role="alert"><Icon name="alert" /><div>
      <p><strong>These labels would point to {settings.data.publicBaseUrl}</strong></p>
      <p>Phones can't open that address. Fine for a test print; before printing labels for real shelves, set BACKROOM_PUBLIC_URL to Backroom's permanent web address.</p>
    </div></div>}
    <div className="labels-layout">
      <section className="panel" aria-labelledby="preview-heading">
        <h2 className="section-heading" id="preview-heading">{previews ? previews.length + (previews.length === 1 ? ' label' : ' labels') : 'Labels'}</h2>
        <p className="section-intro">Each label shows the QR code, the name, one line of detail, and the code to type if a camera can't read it.</p>
        <ErrorMessage error={loadError} retry={() => setRevision(value => value + 1)} />
        {!previews && !loadError && <Loading variant="list" label="Loading labels" />}
        {previews?.length === 0 && <p className="muted">Nothing selected. Open an item or location and choose Print label.</p>}
        <ul className="label-previews">{previews?.map(preview => <li key={preview.type + preview.id} className="label-preview">
          <span className="qr-placeholder" aria-hidden="true"><Icon name="scan" /></span>
          <span className="label-text"><strong>{preview.name}</strong><span>{preview.detail}</span><span className="mono">{preview.code ?? 'New code'}</span></span>
          <button type="button" className="icon-button" aria-label={'Remove ' + preview.name} onClick={() => remove(preview)}><Icon name="close" className="small" /></button>
        </li>)}</ul>
      </section>
      <section className="panel print-options" aria-labelledby="options-heading">
        <h2 className="section-heading" id="options-heading">Paper</h2>
        <fieldset className="layout-options"><legend className="sr-only">Label paper</legend>
          {layouts.map(option => <label key={option.id} className={'layout-option' + (layout === option.id ? ' selected' : '')}>
            <input type="radio" name="layout" value={option.id} checked={layout === option.id} onChange={() => { setLayout(option.id); setStart(1); }} />
            <span><strong>{option.name}</strong><small>{option.detail}</small></span>
          </label>)}
        </fieldset>
        {layout !== 'SINGLE' && <div className="field"><label htmlFor="start">Start at label</label>
          <select id="start" value={start} onChange={event => setStart(Number(event.target.value))}>{Array.from({ length: selected.perPage }, (_, index) =>
            <option key={index} value={index + 1}>{index + 1}{index === 0 ? ' (top left)' : ''}</option>)}</select>
          <small>Skip labels already used on a partly used sheet. Labels run left to right, top to bottom.</small></div>}
        <ol className="print-steps">
          <li>Print the <button type="button" className="text-button" onClick={() => void calibration()}>calibration page</button> on plain paper once, and check its ruler measures 50 mm.</li>
          <li>Print labels at 100% or "Actual size". Turn off "Fit to page".</li>
          <li>Scan one label with a phone before sticking the rest.</li>
        </ol>
        <ErrorMessage error={error} />
        {done && <SuccessMessage>{done}</SuccessMessage>}
        <button type="button" className="button primary full-width" disabled={pending || !previews?.length} onClick={() => void print()}><Icon name="label" />{pending ? 'Preparing PDF…' : 'Download PDF'}</button>
        {settings.data && previews && previews.length > settings.data.maxLabels && <p className="field-help">You can print up to {settings.data.maxLabels} labels at a time.</p>}
      </section>
    </div>
  </>;
}

/** "Print labels" selection mode for the Inventory and Locations lists. */
export function labelDownloadKey(orgId: string) { return 'backroom.labels.downloaded.' + orgId; }

export function useLabelSelection(initial = false) {
  const [selecting, setSelecting] = useState(initial);
  const [selected, setSelected] = useState<string[]>([]);
  return {
    selecting, selected,
    toggleMode: () => { setSelecting(value => !value); setSelected([]); },
    toggle: (id: string) => setSelected(list => list.includes(id) ? list.filter(value => value !== id) : [...list, id]),
  };
}
export function SelectionBar({ count, href, noun }: { count: number; href: string; noun: string }) {
  return <div className="selection-bar" role="status">
    <span>{count ? count + ' ' + noun + (count === 1 ? '' : 's') + ' selected' : 'Choose the ' + noun + 's to label.'}</span>
    {count > 0 && <Link className="button primary compact" to={href}><Icon name="label" />Print {count} {count === 1 ? 'label' : 'labels'}</Link>}
  </div>;
}
