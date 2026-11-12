import { Children, cloneElement, isValidElement, useEffect, useId, useRef, useState } from 'react';
import type { ReactElement, ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Link } from 'react-router';
import { ApiError, api, query } from './api';
import { Icon } from './icons';
import type { IconName } from './icons';
import type { Page } from './types';

export function useResource<T>(path: string | null) {
  const [result, setResult] = useState<{ path: string | null; data?: T; error?: unknown }>({ path: null });
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    if (!path) return;
    const controller = new AbortController();
    setResult({ path });
    void api<T>(path, { signal: controller.signal }).then(data => {
      if (!controller.signal.aborted) setResult({ path, data });
    }).catch(error => {
      if (!controller.signal.aborted) setResult({ path, error });
    });
    return () => controller.abort();
  }, [path, revision]);
  const current = result.path === path ? result : { path };
  return { data: current.data, error: current.error, reload: () => setRevision(value => value + 1) };
}
export function useAll<T>(path: string) {
  const [state, setState] = useState<{ path: string; data?: T[]; error?: unknown }>({ path: '' });
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setState({ path });
    void (async () => {
      const items: T[] = [];
      for (let page = 0; ; page++) {
        const result = await api<Page<T>>(query(path, { page, size: 100 }), { signal: controller.signal });
        items.push(...result.items);
        if (page + 1 >= result.totalPages) break;
      }
      if (!controller.signal.aborted) setState({ path, data: items });
    })().catch(error => { if (!controller.signal.aborted) setState({ path, error }); });
    return () => controller.abort();
  }, [path, revision]);
  return { data: state.path === path ? state.data : undefined, error: state.path === path ? state.error : undefined, reload: () => setRevision(value => value + 1) };
}

/** Partitioned-square mark on honey. */
export function BrandMark() {
  return <svg className="brand-mark" viewBox="0 0 32 32" aria-hidden="true" focusable="false"><rect width="32" height="32" rx="7" fill="var(--brand-mark)" /><path d="M8 8h16v16H8zM8 14h16M14 14v10" fill="none" stroke="var(--brand-ink)" strokeWidth="2.25" strokeLinejoin="round" /></svg>;
}
export function Brand({ compact = false, to = '/' }: { compact?: boolean; to?: string }) {
  return <Link className={'brand' + (compact ? ' compact' : '')} to={to} aria-label="Backroom home"><BrandMark />{!compact && <span>Backroom</span>}</Link>;
}

export function Loading({ variant = 'text', label = 'Loading…' }: { variant?: 'text' | 'list'; label?: string }) {
  if (variant === 'list') return <div className="skeleton" role="status" aria-label={label}><span /><span /><span /><span /></div>;
  return <p role="status" className="loading">{label}</p>;
}
export function ErrorMessage({ error, retry }: { error: unknown; retry?: () => void }) {
  if (!error) return null;
  const problem = error instanceof Error ? error : new Error('Something went wrong. Try again.');
  const fields = problem instanceof ApiError ? Object.entries(problem.fieldErrors) : [];
  return <div className="notice error-message" role="alert"><Icon name="alert" /><div><p>{problem.message}</p>{fields.length > 0 && <ul>{fields.map(([field, text]) => <li key={field}><strong>{field.replace(/([A-Z])/g, ' $1').toLowerCase()}: </strong>{text}</li>)}</ul>}{retry && <button className="button secondary" type="button" onClick={retry}>Try again</button>}</div></div>;
}
export function SuccessMessage({ children }: { children: ReactNode }) {
  return <div className="notice success-message" role="status"><Icon name="check" /><div><p>{children}</p></div></div>;
}
export function Empty({ title, children, icon = 'box', action }: { title: string; children: ReactNode; icon?: IconName; action?: ReactNode }) {
  return <div className="empty-state"><div className="empty-icon"><Icon name={icon} /></div><h2>{title}</h2><p>{children}</p>{action}</div>;
}
export function PageHeader({ title, children, description, back }: { title: string; description?: string; children?: ReactNode; back?: { to: string; label: string } }) {
  return <div className="page-heading">
    {back && <Link className="back-link" to={back.to}><Icon name="chevronLeft" className="small" />{back.label}</Link>}
    <div className="page-heading-row"><div className="page-heading-text"><h1>{title}</h1>{description && <p className="page-description">{description}</p>}</div>{children && <div className="page-actions">{children}</div>}</div>
  </div>;
}
export function Pager<T>({ data, onPage }: { data: Page<T>; onPage: (page: number) => void }) {
  if (data.totalPages <= 1) return null;
  return <nav className="pager" aria-label="Pagination"><span className="numeric">Page {data.page + 1} of {data.totalPages}</span><span className="pager-buttons"><button type="button" className="button secondary" disabled={data.page === 0} onClick={() => onPage(data.page - 1)}><Icon name="chevronLeft" />Previous</button><button type="button" className="button secondary" disabled={data.page + 1 >= data.totalPages} onClick={() => onPage(data.page + 1)}>Next<Icon name="chevronRight" /></button></span></nav>;
}

/**
 * Modal dialog: centered on desktop, bottom sheet on phones.
 * Background is inert, Tab is contained, Escape closes, focus returns to the trigger.
 * Initial focus: an element marked data-autofocus (use on Cancel for destructive tasks),
 * else the first editable field, else the first control.
 */
/** The control that opened the outermost dialog, so a dialog opened from another dialog can still return focus there. */
let dialogTrigger: HTMLElement | null = null;
export function Dialog({ title, children, close }: { title: string; children: ReactNode; close: () => void }) {
  const container = useRef<HTMLElement>(null);
  const closeRef = useRef(close);
  closeRef.current = close;
  const headingId = useId();
  useEffect(() => {
    const active = document.activeElement instanceof HTMLElement && document.activeElement !== document.body ? document.activeElement : null;
    const previous = active?.isConnected ? active : dialogTrigger?.isConnected ? dialogTrigger : null;
    dialogTrigger = previous;
    const root = document.getElementById('root');
    if (root) root.inert = true;
    const focusable = () => Array.from(container.current?.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled])') ?? []);
    const listener = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); closeRef.current(); }
      if (event.key === 'Tab') {
        const elements = focusable(); const first = elements[0]; const last = elements.at(-1);
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
      }
    };
    document.addEventListener('keydown', listener);
    const frame = requestAnimationFrame(() => {
      const box = container.current;
      (box?.querySelector<HTMLElement>('[data-autofocus]') ?? box?.querySelector<HTMLElement>('input:not([readonly]), select, textarea') ?? focusable()[0])?.focus();
    });
    return () => {
      cancelAnimationFrame(frame); document.removeEventListener('keydown', listener);
      if (root) root.inert = false;
      if (previous?.isConnected) previous.focus();
    };
  }, []);
  return createPortal(<div className="dialog-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) closeRef.current(); }}>
    <section ref={container} className="dialog" role="dialog" aria-modal="true" aria-labelledby={headingId}>
      <div className="dialog-heading"><h2 id={headingId}>{title}</h2><button type="button" className="icon-button" aria-label="Close dialog" onClick={close}><Icon name="close" /></button></div>
      <div className="dialog-body">{children}</div>
    </section>
  </div>, document.body);
}

export function Field({ label, children, error }: { label: string; children: ReactNode; error?: string }) {
  const id = useId();
  const help = Children.toArray(children).some(child => isValidElement(child) && child.type === 'small');
  const describedBy = [help ? id + '-help' : '', error ? id + '-error' : ''].filter(Boolean).join(' ') || undefined;
  return <div className="field"><label htmlFor={id}>{label}</label>{Children.map(children, child => {
    if (!isValidElement(child)) return child;
    if (child.type === 'input' || child.type === 'select' || child.type === 'textarea') {
      return cloneElement(child as ReactElement<{ id?: string; 'aria-describedby'?: string; 'aria-invalid'?: boolean }>, { id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined });
    }
    if (child.type === 'small') return cloneElement(child as ReactElement<{ id?: string }>, { id: id + '-help' });
    return child;
  })}{error && <p className="field-error" id={id + '-error'}><Icon name="alert" className="small" />{error}</p>}</div>;
}
export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'success' | 'warning' | 'danger' | 'accent' }) {
  return <span className={'badge' + (tone === 'neutral' ? '' : ' ' + tone)}>{children}</span>;
}
