import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { api, send } from './api';
import { Badge, Dialog, ErrorMessage, Field, PageHeader } from './components';
import { Icon } from './icons';
import type { Item, Page, ScanResult } from './types';
import { unitNames } from './types';
import { useWorkspace } from './workspace';

const FORMATS = ['qr_code', 'ean_13', 'ean_8', 'upc_a', 'upc_e'];
interface Detected { rawValue: string; format: string }
interface Detector { detect(source: HTMLVideoElement): Promise<Detected[]> }

/**
 * Native BarcodeDetector where the browser has one (Chrome on Android); otherwise the zxing-cpp WebAssembly
 * polyfill, loaded only when the camera starts and served from our own assets (no CDN).
 */
async function createDetector(): Promise<Detector> {
  const native = (window as unknown as { BarcodeDetector?: { new (options: { formats: string[] }): Detector; getSupportedFormats(): Promise<string[]> } }).BarcodeDetector;
  if (native) {
    try {
      const supported = await native.getSupportedFormats();
      const usable = FORMATS.filter(format => supported.includes(format));
      if (usable.includes('qr_code')) return new native({ formats: usable });
    } catch { /* fall back to the bundled reader */ }
  }
  const [{ BarcodeDetector, prepareZXingModule }, wasm] = await Promise.all([
    import('barcode-detector/ponyfill'),
    import('zxing-wasm/reader/zxing_reader.wasm?url'),
  ]);
  prepareZXingModule({ overrides: { locateFile: (path: string, prefix: string) => path.endsWith('.wasm') ? wasm.default : prefix + path } });
  return new BarcodeDetector({ formats: FORMATS as never[] }) as unknown as Detector;
}

type CameraProblem = 'denied' | 'missing' | 'insecure' | 'failed';
const problems: Record<CameraProblem, { title: string; text: string }> = {
  denied: { title: 'Camera access is off', text: 'Allow camera access for this site, then try again. On iPhone: Settings › Safari › Camera, or tap "aA" in the address bar › Website Settings.' },
  missing: { title: 'No camera found', text: 'This device has no camera Backroom can use. Type the code printed under the QR code instead.' },
  insecure: { title: 'Camera needs a secure connection', text: 'Browsers only allow the camera on HTTPS pages. Open Backroom through its https:// address, or type the code below.' },
  failed: { title: 'The camera could not start', text: 'Close other apps using the camera and try again, or type the code below.' },
};

export function ScanPage() {
  const { base, organization } = useWorkspace();
  const root = '/app/organizations/' + organization.id;
  const video = useRef<HTMLVideoElement>(null);
  const stream = useRef<MediaStream | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const [phase, setPhase] = useState<'idle' | 'starting' | 'scanning' | 'paused'>('idle');
  const [problem, setProblem] = useState<CameraProblem>();
  const [result, setResult] = useState<ScanResult>();
  const [scanned, setScanned] = useState<{ value: string; format?: string }>();
  const [error, setError] = useState<unknown>();
  const [resolving, setResolving] = useState(false);
  const [matches, setMatches] = useState<Item[]>();
  const [linking, setLinking] = useState(false);
  const resultArea = useRef<HTMLDivElement>(null);

  const stopCamera = () => {
    window.clearTimeout(timer.current);
    stream.current?.getTracks().forEach(track => track.stop());
    stream.current = null;
  };
  useEffect(() => stopCamera, []);
  // On phones the result sits below the camera; bring it into view as soon as it arrives.
  useEffect(() => {
    if (!result && !matches) return;
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    resultArea.current?.scrollIntoView({ block: 'nearest', behavior: reduce ? 'auto' : 'smooth' });
  }, [result, matches]);

  async function resolve(value: string, format?: string, manual = false) {
    setScanned({ value, format }); setResolving(true); setError(undefined); setResult(undefined); setMatches(undefined);
    try {
      const found = await send<ScanResult>(base + '/scan-resolutions', 'POST', { value, format: format ?? null });
      setResult(found);
      // Typed words that aren't a code: offer matching items instead of a dead end.
      if (manual && (found.status === 'UNSUPPORTED' || found.status === 'NOT_FOUND') && /[a-z]/i.test(value)) {
        const page = await api<Page<Item>>(base + '/items?size=8&q=' + encodeURIComponent(value.trim()));
        setMatches(page.items);
      }
    } catch (value) { setError(value); } finally { setResolving(false); }
  }

  async function start() {
    setProblem(undefined); setError(undefined); setResult(undefined); setMatches(undefined);
    if (!window.isSecureContext) { setProblem('insecure'); return; }
    if (!navigator.mediaDevices?.getUserMedia) { setProblem('missing'); return; }
    setPhase('starting');
    try {
      stream.current ??= await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } }, audio: false });
      const element = video.current!;
      element.srcObject = stream.current;
      await element.play();
      const detector = await createDetector();
      setPhase('scanning');
      const tick = async () => {
        if (!stream.current) return;
        try {
          if (element.readyState >= 2) {
            const codes = await detector.detect(element);
            if (codes.length) {
              setPhase('paused'); // pause after a successful read; nothing happens until the person acts
              if ('vibrate' in navigator) navigator.vibrate?.(40);
              void resolve(codes[0].rawValue, codes[0].format);
              return;
            }
          }
        } catch { /* a frame that can't be read is normal; keep looking */ }
        timer.current = window.setTimeout(() => void tick(), 180);
      };
      void tick();
    } catch (failure) {
      stopCamera(); setPhase('idle');
      const name = failure instanceof DOMException ? failure.name : '';
      setProblem(name === 'NotAllowedError' || name === 'SecurityError' ? 'denied' : name === 'NotFoundError' || name === 'OverconstrainedError' ? 'missing' : 'failed');
    }
  }

  function scanAgain() {
    setResult(undefined); setMatches(undefined); setError(undefined); setScanned(undefined);
    if (stream.current) { setPhase('starting'); void start(); } else setPhase('idle');
  }

  function lookUp(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = String(new FormData(event.currentTarget).get('code') ?? '').trim();
    if (value) { stopCamera(); setPhase('idle'); void resolve(value, undefined, true); }
  }

  const cameraOn = phase === 'starting' || phase === 'scanning' || phase === 'paused';
  return <>
    <PageHeader title="Scan" description="Point the camera at a Backroom label or a product barcode." />
    <div className="scan-layout">
      <section className="panel scan-camera" aria-label="Camera">
        <div className={'viewfinder' + (cameraOn ? ' on' : '')}>
          <video ref={video} muted playsInline aria-label="Camera preview" />
          {cameraOn && <div className="aim" aria-hidden="true" />}
          {!cameraOn && <div className="viewfinder-idle"><Icon name="scan" /><p>The camera stays off until you start it.</p></div>}
          <p className="sr-only" role="status">{phase === 'scanning' ? 'Scanning. Hold the label inside the frame.' : phase === 'paused' ? 'Code found.' : ''}</p>
        </div>
        <div className="scan-controls">
          {phase === 'idle' && <button type="button" className="button primary" onClick={() => void start()}><Icon name="scan" />Start camera</button>}
          {phase === 'starting' && <button type="button" className="button primary" disabled>Starting camera…</button>}
          {phase === 'scanning' && <p className="muted">Hold the label inside the frame, about a hand's width away.</p>}
          {cameraOn && <button type="button" className="button secondary" onClick={() => { stopCamera(); setPhase('idle'); }}>Stop camera</button>}
        </div>
        {problem && <div className="notice error-message" role="alert"><Icon name="alert" /><div><p><strong>{problems[problem].title}</strong></p><p>{problems[problem].text}</p></div></div>}
      </section>

      <section className="scan-side" aria-label="Result and manual entry">
        <div className="scan-results" ref={resultArea}>
        <ErrorMessage error={error} retry={scanned ? () => void resolve(scanned.value, scanned.format) : undefined} />
        {result && <ResultCard result={result} scanned={scanned?.value ?? ''} root={root} linkBarcode={() => setLinking(true)} />}
        {matches && <div className="panel table-panel">
          <div className="panel-header"><div><h2>Items matching “{scanned?.value}”</h2></div></div>
          {matches.length === 0 ? <p className="panel-body muted">No items with that name or SKU.</p> : <ul className="record-list match-list">{matches.map(item =>
            <li key={item.id}><Link className="record-link" to={root + '/items/' + item.id}>{item.name}</Link><span className="muted">{item.sku ?? unitNames[item.unit]}</span></li>)}</ul>}
        </div>}
        {(result || matches) && <button type="button" className="button primary full-width" onClick={scanAgain}><Icon name="scan" />Scan again</button>}
        </div>
        <form className="panel manual-entry" onSubmit={lookUp}>
          <Field label="Type a code or barcode"><input name="code" autoComplete="off" autoCapitalize="characters" spellCheck={false} placeholder="7K4Q-2M9X-QA or 036000291452" /><small>The code is printed under each QR label. You can also type an item name.</small></Field>
          <button className="button secondary" disabled={resolving}>{resolving ? 'Looking up…' : 'Look up'}</button>
        </form>
      </section>
    </div>
    {linking && result?.code && <LinkBarcodeDialog base={base} code={result.code} format={scanned?.format} close={() => setLinking(false)}
      linked={() => { setLinking(false); void resolve(result.code!, scanned?.format); }} />}
  </>;
}

function ResultCard({ result, scanned, root, linkBarcode }: { result: ScanResult; scanned: string; root: string; linkBarcode: () => void }) {
  const navigate = useNavigate();
  if (result.status === 'ITEM' || result.status === 'LOCATION') {
    const item = result.status === 'ITEM';
    const href = root + (item ? '/items/' : '/locations/') + result.id;
    return <div className="panel scan-result found" role="status">
      <p className="label-kicker"><Icon name={item ? 'catalog' : 'location'} className="small" />{item ? 'Item' : 'Location'} · <span className="mono">{result.code}</span></p>
      <h2>{result.name}</h2>
      <p className="muted">{item ? [result.sku, result.unit ? unitNames[result.unit] : null].filter(Boolean).join(' · ') : result.parentName ? 'in ' + result.parentName : 'Top-level location'}</p>
      {result.archived ? <p><Badge tone="warning">Archived</Badge> <span className="muted">You can view it, but stock can't change.</span></p>
        : <div className="result-actions">
          <Link className="button primary" to={href}>{item ? 'Open item' : 'Open location'}</Link>
          {item && <>
            <button type="button" className="button secondary" onClick={() => navigate(href + '?action=usage')}><Icon name="use" />Record usage</button>
            <button type="button" className="button secondary" onClick={() => navigate(href + '?action=receive')}><Icon name="receive" />Receive</button>
          </>}
        </div>}
    </div>;
  }
  const messages: Record<string, { title: string; text: string }> = {
    REVOKED: { title: 'This label was withdrawn', text: 'It no longer points anywhere. Ask a manager to print a new label for this item or location.' },
    NOT_FOUND: { title: 'No label with this code', text: 'Check the code and try again. It may belong to a business you are not part of.' },
    UNSUPPORTED: { title: 'Not a Backroom label or product barcode', text: 'Backroom only reads its own QR labels and UPC/EAN barcodes. Other links are never opened.' },
    NOT_REGISTERED: { title: 'Barcode ' + (result.code ?? '') + ' isn’t linked yet', text: result.canRegister ? 'Link it to an item once, and scanning it will open that item from then on.' : 'Ask a manager to link this barcode to an item.' },
    OTHER_ORGANIZATION: { title: 'This label belongs to ' + (result.organizationName ?? 'another business'), text: 'You are a member there too. Switch to open it.' },
  };
  const message = messages[result.status];
  return <div className={'panel scan-result' + (result.status === 'NOT_REGISTERED' ? '' : ' warning')} role="status">
    <h2>{message.title}</h2>
    <p className="muted">{message.text}</p>
    {result.status === 'UNSUPPORTED' && scanned && <p className="scanned-text mono">{scanned}</p>}
    {result.status === 'NOT_REGISTERED' && result.canRegister && <div className="result-actions"><button type="button" className="button primary" onClick={linkBarcode}>Link to an item</button></div>}
    {result.status === 'OTHER_ORGANIZATION' && result.code && <div className="result-actions"><Link className="button primary" to={'/s/' + result.code.replaceAll('-', '')}>Switch to {result.organizationName}</Link></div>}
  </div>;
}

function LinkBarcodeDialog({ base, code, format, close, linked }: { base: string; code: string; format?: string; close: () => void; linked: () => void }) {
  const [items, setItems] = useState<Item[]>();
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  async function search(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    const q = event ? String(new FormData(event.currentTarget).get('q') ?? '') : '';
    try { setItems((await api<Page<Item>>(base + '/items?size=20&q=' + encodeURIComponent(q.trim()))).items); } catch (value) { setError(value); }
  }
  useEffect(() => { void search(); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  async function choose(item: Item) {
    setPending(true); setError(undefined);
    try { await send(base + '/items/' + item.id + '/barcodes', 'POST', { value: code, format: format ?? null }); linked(); }
    catch (value) { setError(value); } finally { setPending(false); }
  }
  return <Dialog title={'Link barcode ' + code} close={close}>
    <p>Choose the item this product barcode belongs to. Each barcode can belong to one item.</p>
    <form className="search-form dialog-search" role="search" onSubmit={event => void search(event)}>
      <div className="search-input"><Icon name="search" /><input name="q" type="search" aria-label="Search items" placeholder="Search by name or SKU" /></div>
      <button className="button secondary">Search</button>
    </form>
    <ErrorMessage error={error} />
    <ul className="record-list pick-list">{items?.map(item => <li key={item.id}>
      <span><strong>{item.name}</strong><span className="cell-secondary">{item.sku ?? 'No SKU'} · {unitNames[item.unit]}</span></span>
      <button type="button" className="button secondary compact" disabled={pending} onClick={() => void choose(item)}>Link<span className="sr-only"> to {item.name}</span></button>
    </li>)}</ul>
    {items?.length === 0 && <p className="muted">No items match. Add the item in Inventory first.</p>}
  </Dialog>;
}
