import { useState } from 'react';
import type { FormEvent } from 'react';
import { download, query } from './api';
import { ErrorMessage, Field, PageHeader, SuccessMessage } from './components';
import { Icon } from './icons';
import { useWorkspace } from './workspace';

type ExportFile = 'items' | 'stock' | 'movements' | 'requests';
const exports: { file: ExportFile; title: string; description: string; dated: boolean }[] = [
  { file: 'stock', title: 'Stock by location', description: 'Every item at every location with its quantity, minimum and whether it is low. A snapshot of right now.', dated: false },
  { file: 'items', title: 'Items', description: 'Your catalog: names, SKUs, units, total on hand and whether each item is archived.', dated: false },
  { file: 'movements', title: 'Stock history', description: 'Every receipt, usage, waste, transfer, count and reversal, with who recorded it.', dated: true },
  { file: 'requests', title: 'Restock requests', description: 'Requests with their status, approved and received quantities, and decisions.', dated: true },
];

const isoDay = (date: Date) => date.toISOString().slice(0, 10);

function ExportCard({ entry }: { entry: typeof exports[number] }) {
  const { base } = useWorkspace();
  const today = isoDay(new Date());
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>();
  const [done, setDone] = useState('');
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const from = entry.dated ? String(form.get('from')) : '';
    const to = entry.dated ? String(form.get('to')) : '';
    const filename = 'backroom-' + entry.file + (entry.dated ? '-' + from + '-to-' + to : '-' + today) + '.csv';
    setPending(true); setError(undefined); setDone('');
    try {
      await download(query(base + '/exports/' + entry.file + '.csv', entry.dated ? { from, to } : {}), filename);
      setDone('Downloaded ' + filename);
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  const headingId = 'export-' + entry.file;
  return <section className="panel export-card" aria-labelledby={headingId}>
    <h2 id={headingId} className="section-heading">{entry.title}</h2>
    <p className="section-intro">{entry.description}</p>
    <form onSubmit={submit}>
      {entry.dated && <div className="date-range">
        <Field label="From"><input type="date" name="from" required defaultValue={isoDay(new Date(Date.now() - 90 * 86_400_000))} max={today} /></Field>
        <Field label="To"><input type="date" name="to" required defaultValue={today} max={today} /></Field>
      </div>}
      <ErrorMessage error={error} />
      {done && <SuccessMessage>{done}</SuccessMessage>}
      <button className="button secondary" disabled={pending}><Icon name="download" />{pending ? 'Preparing…' : 'Download CSV'}<span className="sr-only"> for {entry.title}</span></button>
    </form>
  </section>;
}

export function ExportsPage() {
  const { organization } = useWorkspace();
  if (organization.role === 'EMPLOYEE') return <ErrorMessage error={new Error('Only Owners and Managers can export data.')} />;
  return <>
    <PageHeader title="Export data" description="Spreadsheets for your records or your accountant. They open in Excel, Numbers and Google Sheets." />
    <div className="export-grid">{exports.map(entry => <ExportCard key={entry.file} entry={entry} />)}</div>
    <p className="muted export-note">Dates and times are in UTC. Each file holds up to 50,000 rows; choose a shorter range if a history export is larger. Every export is recorded in Activity.</p>
  </>;
}
