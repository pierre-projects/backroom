import { useId, useRef, useState } from 'react';
import { Link } from 'react-router';
import { api, download, send } from './api';
import { Badge, ErrorMessage, PageHeader, SuccessMessage } from './components';
import { Icon } from './icons';
import { useWorkspace } from './workspace';

type RowStatus = 'NEW' | 'DUPLICATE' | 'POSSIBLE_DUPLICATE' | 'ERROR';
interface PreviewRow { line: number; item: string; sku: string | null; unit: string; location: string; quantity: string; minimum: string; status: RowStatus; messages: string[] }
interface Preview {
  summary: { rows: number; newItems: number; duplicates: number; possibleDuplicates: number; errors: number; placements: number; quantities: number; minimums: number; locationsToCreate: string[] };
  rows: PreviewRow[];
}
interface Result { replayed: boolean; rows: number; itemsCreated: number; locationsCreated: number; placements: number; quantities: number; minimums: number; skipped: number; errors: number }

const MAX_BYTES = 1024 * 1024;
const statusLabels: Record<RowStatus, string> = { NEW: 'New', DUPLICATE: 'Already exists', POSSIBLE_DUPLICATE: 'Possible duplicate', ERROR: 'Problem' };
const statusTones: Record<RowStatus, 'success' | 'neutral' | 'warning' | 'danger'> = { NEW: 'success', DUPLICATE: 'neutral', POSSIBLE_DUPLICATE: 'warning', ERROR: 'danger' };
const columns: [string, string, string][] = [
  ['Item', 'Required', 'House espresso'],
  ['Unit', 'Required', 'each, g, kg, mL or L'],
  ['SKU', 'Optional', 'COF-101'],
  ['Location', 'Optional', 'Stockroom > Coffee shelf'],
  ['Starting quantity', 'Optional, needs a location', '12'],
  ['Minimum', 'Optional, needs a location', '4'],
];

/** Import items from a spreadsheet: choose a file, review what will happen, then import. Owners and Managers. */
export function ImportItemsPage() {
  const { base, canEdit, organization } = useWorkspace();
  const root = '/app/organizations/' + organization.id;
  const input = useId();
  const [file, setFile] = useState<{ name: string; text: string }>();
  const [preview, setPreview] = useState<Preview>();
  const [include, setInclude] = useState<number[]>([]);
  const [result, setResult] = useState<Result>();
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  // One key per exact request (file + included rows), so a retried import replays instead of running twice.
  const key = useRef<string>('');
  if (!canEdit) return <ErrorMessage error={new Error('Only Owners and Managers can import items.')} />;

  async function choose(chosen: File | undefined) {
    if (!chosen) return;
    setError(undefined); setPreview(undefined); setResult(undefined); setInclude([]);
    if (chosen.size > MAX_BYTES) { setError(new Error('That file is larger than 1 MB. Split it into smaller files.')); return; }
    setPending(true);
    try {
      const text = await chosen.text();
      const loaded = await api<Preview>(base + '/imports/items/preview', { method: 'POST', body: new Blob([text], { type: 'text/csv' }) });
      setFile({ name: chosen.name, text }); setPreview(loaded); key.current = crypto.randomUUID();
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  function toggle(line: number) {
    setInclude(current => current.includes(line) ? current.filter(entry => entry !== line) : [...current, line]);
    key.current = crypto.randomUUID();
  }
  async function apply() {
    if (!file) return;
    setPending(true); setError(undefined);
    try { setResult(await send<Result>(base + '/imports/items', 'POST', { importKey: key.current, fileName: file.name, csv: file.text, include })); }
    catch (value) { setError(value); } finally { setPending(false); }
  }
  function restart() { setFile(undefined); setPreview(undefined); setResult(undefined); setInclude([]); setError(undefined); }

  const importing = preview ? preview.rows.filter(row => row.status === 'NEW' || include.includes(row.line)).length : 0;
  const header = <PageHeader back={{ to: root + '/items', label: 'Inventory' }} title="Import items"
    description="Add many items at once from a spreadsheet, with their places and starting quantities." />;

  if (result) {
    const facts = [[result.itemsCreated, 'item', 'items', 'created'], [result.locationsCreated, 'location', 'locations', 'created'],
      [result.quantities, 'starting quantity', 'starting quantities', 'recorded'], [result.minimums, 'minimum', 'minimums', 'set'],
      [result.skipped, 'row', 'rows', 'skipped as duplicates'], [result.errors, 'row', 'rows', 'not imported because of problems']] as const;
    return <>{header}
      <section className="panel import-result" aria-labelledby="import-done">
        <h2 id="import-done" className="section-heading">Import finished</h2>
        <SuccessMessage>{result.replayed ? 'This import had already run; nothing was added twice.' : 'Your items are in Backroom.'}</SuccessMessage>
        <ul className="import-facts">{facts.filter(([count]) => count > 0).map(([count, one, many, verb]) =>
          <li key={one + verb}><strong className="numeric">{count}</strong> {count === 1 ? one : many} {verb}</li>)}</ul>
        <div className="form-actions start"><Link className="button primary" to={root + '/items'}>Open Inventory</Link>
          <Link className="button secondary" to={root + '/locations'}>Open Locations</Link>
          <button type="button" className="button ghost" onClick={restart}>Import another file</button></div>
      </section></>;
  }

  return <>{header}
    {!preview && <section className="panel import-start" aria-labelledby="import-file">
      <h2 id="import-file" className="section-heading">Choose a file</h2>
      <p className="section-intro">Each row is one item. Add a location to put it on a shelf. Locations that don't exist yet are created for you. Nothing is saved until you review it.</p>
      <table className="data-table import-columns">
        <thead><tr><th scope="col">Column</th><th scope="col">Needed?</th><th scope="col">Example</th></tr></thead>
        <tbody>{columns.map(([name, needed, example]) => <tr key={name}><td><strong>{name}</strong></td><td className="cell-secondary-text">{needed}</td><td className="mono">{example}</td></tr>)}</tbody>
      </table>
      <p className="field-help">Save from Excel, Numbers or Google Sheets as CSV, up to 1 MB and 2,000 rows. A Backroom stock export can be imported as it is.</p>
      <ErrorMessage error={error} />
      <div className="form-actions start">
        <input id={input} className="sr-only" type="file" accept=".csv,text/csv" disabled={pending}
          onChange={event => { const chosen = event.currentTarget.files?.[0]; event.currentTarget.value = ''; void choose(chosen); }} />
        <label htmlFor={input} className="button primary file-button"><Icon name="upload" />{pending ? 'Reading…' : 'Choose a CSV file'}</label>
        <button type="button" className="button secondary" onClick={() => void download(base + '/imports/items/template.csv', 'backroom-import-template.csv').catch(setError)}>
          <Icon name="download" />Download template</button>
      </div>
    </section>}

    {preview && <>
      <section className="panel import-review" aria-labelledby="import-review">
        <h2 id="import-review" className="section-heading">Review {file?.name}</h2>
        <ul className="import-counts" aria-label="What this file contains">
          <li><Badge tone="success">{preview.summary.newItems} new {preview.summary.newItems === 1 ? 'item' : 'items'}</Badge></li>
          {preview.summary.duplicates > 0 && <li><Badge>{preview.summary.duplicates} already {preview.summary.duplicates === 1 ? 'exists' : 'exist'}</Badge></li>}
          {preview.summary.possibleDuplicates > 0 && <li><Badge tone="warning">{preview.summary.possibleDuplicates} possible {preview.summary.possibleDuplicates === 1 ? 'duplicate' : 'duplicates'}</Badge></li>}
          {preview.summary.errors > 0 && <li><Badge tone="danger">{preview.summary.errors} with problems</Badge></li>}
          {preview.summary.locationsToCreate.length > 0 && <li><Badge tone="accent">{preview.summary.locationsToCreate.length} new {preview.summary.locationsToCreate.length === 1 ? 'location' : 'locations'}</Badge></li>}
        </ul>
        {preview.summary.locationsToCreate.length > 0 && <details className="optional-details" open={preview.summary.locationsToCreate.length <= 5}>
          <summary>Locations that will be created</summary>
          <ul className="import-locations">{preview.summary.locationsToCreate.map(path => <li key={path}><Icon name="location" className="small" />{path}</li>)}</ul>
        </details>}
        {preview.summary.errors > 0 && <p className="field-help">Rows with problems won't be imported. Fix them in your spreadsheet and choose the file again, or import the other rows now.</p>}
        {preview.summary.duplicates > 0 && <p className="field-help">Items whose SKU already exists are left exactly as they are.</p>}
        <ErrorMessage error={error} />
        <div className="form-actions start">
          <button type="button" className="button primary" disabled={pending || importing === 0} onClick={() => void apply()}>
            {pending ? 'Importing…' : importing === 0 ? 'Nothing to import' : 'Import ' + importing + (importing === 1 ? ' row' : ' rows')}</button>
          <button type="button" className="button secondary" disabled={pending} onClick={restart}>Choose another file</button>
        </div>
      </section>
      <section className="panel table-panel" aria-labelledby="import-rows">
        <div className="panel-header"><div><h2 id="import-rows">Rows</h2><p>Line numbers match your spreadsheet.</p></div></div>
        <table className="data-table import-table">
          <thead><tr><th scope="col">Line</th><th scope="col">Item</th><th scope="col">Place and quantity</th><th scope="col">Status</th></tr></thead>
          <tbody>{preview.rows.map(row => <tr key={row.line} className={'import-row ' + row.status.toLowerCase()}>
            <td className="numeric cell-secondary-text import-line"><span className="import-line-label">Line </span>{row.line}</td>
            <td><strong>{row.item || '—'}</strong><span className="cell-secondary">{[row.sku, row.unit].filter(Boolean).join(' · ')}</span></td>
            <td>{row.location || <span className="muted">No place</span>}
              {(row.quantity || row.minimum) && <span className="cell-secondary numeric">{[row.quantity && row.quantity + ' to start', row.minimum && 'min ' + row.minimum].filter(Boolean).join(' · ')}</span>}</td>
            <td><Badge tone={include.includes(row.line) ? 'success' : statusTones[row.status]}>{include.includes(row.line) ? 'Included' : statusLabels[row.status]}</Badge>
              {row.messages.map(message => <span key={message} className="import-message">{message}</span>)}
              {row.status === 'POSSIBLE_DUPLICATE' && <label className="checkbox import-include"><input type="checkbox" checked={include.includes(row.line)} onChange={() => toggle(row.line)} /> Include<span className="sr-only"> line {row.line}, {row.item}</span></label>}</td>
          </tr>)}</tbody>
        </table>
      </section>
    </>}
  </>;
}
