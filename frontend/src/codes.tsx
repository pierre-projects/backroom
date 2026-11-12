import { useEffect, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router';
import { ApiError, api, send } from './api';
import { useAuth } from './auth';
import { Badge, Brand, Dialog, ErrorMessage, Field, Loading, SuccessMessage, useResource } from './components';
import { Icon } from './icons';
import type { CodeLink, ResourceCode, TargetType } from './types';
import { useWorkspace } from './workspace';

/**
 * Where a printed QR label leads when opened with the phone's own camera: /s/{token}.
 * Signed-out visitors sign in first and come straight back here; then we open the item or location.
 */
export function CodeLinkPage() {
  const { token = '' } = useParams();
  const auth = useAuth();
  const navigate = useNavigate();
  const [state, setState] = useState<'loading' | 'revoked' | 'unavailable' | 'error'>('loading');
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!auth.user) return;
    let active = true;
    setState('loading');
    void api<CodeLink>('/codes/' + encodeURIComponent(token)).then(link => {
      if (!active) return;
      if (link.status === 'REVOKED' || !link.id) { setState('revoked'); return; }
      navigate('/app/organizations/' + link.organizationId + (link.targetType === 'ITEM' ? '/items/' : '/locations/') + link.id, { replace: true });
    }).catch(error => { if (active) setState(error instanceof ApiError && error.status === 404 ? 'unavailable' : 'error'); });
    return () => { active = false; };
  }, [auth.user, token, attempt, navigate]);

  if (auth.loading) return <div className="auth-shell"><Loading /></div>;
  if (!auth.user) return <Navigate to={'/signin?next=' + encodeURIComponent('/s/' + token)} replace />;
  const card = (title: string, text: string, extra?: ReactNode) => <div className="auth-shell"><Brand to="/app" /><section className="auth-card">
    <h1>{title}</h1><p className="auth-lead">{text}</p>{extra}<Link className="button secondary full-width" to="/app">Go to your businesses</Link></section></div>;
  if (state === 'revoked') return card('This label was withdrawn', 'It no longer opens anything. Ask a manager to print a new label.');
  if (state === 'unavailable') return card('This label isn’t available to you', 'It may belong to a business you are not a member of, or the code may be mistyped.');
  if (state === 'error') return card('Couldn’t open this label', 'Check your connection and try again.', <button type="button" className="button primary full-width" onClick={() => setAttempt(value => value + 1)}>Try again</button>);
  return <div className="auth-shell"><Loading label="Opening label…" /></div>;
}

/** The codes that identify one item or location, and the actions managers can take on them. */
export function CodesPanel({ type, targetId, archived }: { type: TargetType; targetId: string; archived: boolean }) {
  const { base, canEdit, organization } = useWorkspace();
  const codes = useResource<ResourceCode[]>(base + '/codes?' + (type === 'ITEM' ? 'itemId=' : 'locationId=') + targetId);
  const [withdrawing, setWithdrawing] = useState<ResourceCode>();
  const [error, setError] = useState<unknown>();
  const [status, setStatus] = useState('');
  const [pending, setPending] = useState(false);
  const manage = canEdit && !archived;
  const list = codes.data ?? [];
  const qr = list.find(code => code.kind === 'GENERATED' && code.status === 'ACTIVE');
  const barcodes = list.filter(code => code.kind === 'MANUFACTURER' && code.status === 'ACTIVE');
  const retired = list.filter(code => code.status === 'REVOKED');
  const printHref = '/app/organizations/' + organization.id + '/labels?' + (type === 'ITEM' ? 'items=' : 'locations=') + targetId;

  async function addBarcode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const value = String(new FormData(form).get('barcode') ?? '').trim();
    setPending(true); setError(undefined); setStatus('');
    try {
      const code = await send<ResourceCode>(base + '/items/' + targetId + '/barcodes', 'POST', { value, format: null });
      setStatus('Barcode ' + code.display + ' linked. Scanning it now opens this item.');
      form.reset(); codes.reload();
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  async function withdraw(code: ResourceCode) {
    setPending(true); setError(undefined); setStatus('');
    try {
      await send(base + '/codes/' + code.id + '/revoke', 'POST', { expectedVersion: code.version });
      setWithdrawing(undefined);
      setStatus(code.kind === 'GENERATED' ? 'Label ' + code.display + ' withdrawn. Print a new label to replace it.' : 'Barcode ' + code.display + ' removed.');
      codes.reload();
    } catch (value) { setError(value); } finally { setPending(false); }
  }

  return <section className="panel table-panel" aria-labelledby={'codes-' + targetId}>
    <div className="panel-header"><div><h2 id={'codes-' + targetId}>Labels and barcodes</h2><p>{type === 'ITEM' ? 'Scanning any of these opens this item.' : 'Scanning this label opens this location.'}</p></div>
      {manage && <Link className="button secondary compact" to={printHref}><Icon name="label" />{qr ? 'Print label' : 'Create label'}</Link>}</div>
    <ErrorMessage error={codes.error ?? error} retry={codes.error ? codes.reload : undefined} />
    {status && <div className="panel-body"><SuccessMessage>{status}</SuccessMessage></div>}
    {!codes.data && !codes.error && <Loading variant="list" label="Loading codes" />}
    {codes.data && <ul className="record-list code-list">
      <li><span className="record-main"><Icon name="scan" /><span><strong>QR label</strong><span className="cell-secondary">{qr ? <span className="mono">{qr.display}</span> : 'Not printed yet'}</span></span></span>
        {qr && manage && <button type="button" className="text-button danger" onClick={() => setWithdrawing(qr)}>Withdraw<span className="sr-only"> label {qr.display}</span></button>}</li>
      {type === 'ITEM' && barcodes.map(code => <li key={code.id}><span className="record-main"><Icon name="label" /><span><strong>Product barcode</strong><span className="cell-secondary mono">{code.display}</span></span></span>
        {manage && <button type="button" className="text-button danger" onClick={() => setWithdrawing(code)}>Remove<span className="sr-only"> barcode {code.display}</span></button>}</li>)}
    </ul>}
    {type === 'ITEM' && manage && <form className="panel-footer inline-form barcode-form" onSubmit={addBarcode}>
      <Field label="Add a product barcode"><input name="barcode" inputMode="numeric" autoComplete="off" required pattern="[0-9 \-]{8,17}" title="UPC or EAN digits" placeholder="036000291452" /><small>The digits under the barcode on the packaging (UPC or EAN). You can also scan it from the Scan tab.</small></Field>
      <button className="button secondary" disabled={pending}>{pending ? 'Adding…' : 'Add barcode'}</button>
    </form>}
    {retired.length > 0 && <p className="panel-footer retired muted">Withdrawn: {retired.map(code => <span key={code.id} className="mono">{code.display} </span>)}<Badge>no longer scan</Badge></p>}
    {withdrawing && <Dialog title={withdrawing.kind === 'GENERATED' ? 'Withdraw label ' + withdrawing.display + '?' : 'Remove barcode ' + withdrawing.display + '?'} close={() => setWithdrawing(undefined)}>
      <p>{withdrawing.kind === 'GENERATED'
        ? 'Printed copies of this label will stop working, for good. Use this when a label is lost or stuck in the wrong place, then print a new one.'
        : 'Scanning this barcode will no longer open the item. You can link it to another item afterwards.'}</p>
      <ErrorMessage error={error} />
      <div className="form-actions"><button type="button" className="button secondary" data-autofocus onClick={() => setWithdrawing(undefined)}>Cancel</button>
        <button type="button" className="button danger" disabled={pending} onClick={() => void withdraw(withdrawing)}>{pending ? 'Saving…' : withdrawing.kind === 'GENERATED' ? 'Withdraw' : 'Remove'}</button></div>
    </Dialog>}
  </section>;
}
