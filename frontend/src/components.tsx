import { Children, cloneElement, isValidElement, useEffect, useId, useRef, useState } from 'react';
import type { ReactElement, ReactNode } from 'react';
import { Link } from 'react-router';
import { ApiError, api, query } from './api';
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
export function Brand() {
  return <Link className="brand" to="/" aria-label="Backroom home"><svg className="brand-mark" viewBox="0 0 32 32" fill="none" aria-hidden="true"><path d="M5 5h22v22H5zM5 13h22M13 13v14" stroke="currentColor" strokeWidth="2.5" /></svg>Backroom<span className="brand-period">.</span></Link>;
}
export function Loading() { return <p role="status" className="muted loading">Loading…</p>; }
export function ErrorMessage({ error, retry }: { error: unknown; retry?: () => void }) {
  if (!error) return null;
  const problem = error instanceof Error ? error : new Error('Something went wrong. Try again.');
  return <div className="error-message" role="alert"><p>{problem.message}</p>{problem instanceof ApiError && Object.entries(problem.fieldErrors).length > 0 && <ul>{Object.entries(problem.fieldErrors).map(([field, text]) => <li key={field}><strong>{field.replace(/([A-Z])/g, ' $1')}: </strong>{text}</li>)}</ul>}{retry && <button className="button secondary" type="button" onClick={retry}>Reload</button>}</div>;
}
export function Empty({ title, children }: { title: string; children: ReactNode }) {
  return <div className="empty-state"><div className="empty-icon" aria-hidden="true">▤</div><h2>{title}</h2><p>{children}</p></div>;
}
export function PageHeader({ eyebrow, title, children, description }: { eyebrow?: string; title: string; description?: string; children?: ReactNode }) {
  return <div className="page-heading"><div>{eyebrow && <div className="eyebrow">{eyebrow}</div>}<h1>{title}</h1>{description && <p className="muted">{description}</p>}</div><div className="page-actions">{children}</div></div>;
}
export function Pager<T>({ data, onPage }: { data: Page<T>; onPage: (page: number) => void }) {
  if (data.totalPages <= 1) return null;
  return <div className="pager"><button type="button" className="button secondary" disabled={data.page === 0} onClick={() => onPage(data.page - 1)}>Previous</button><span>Page {data.page + 1} of {data.totalPages}</span><button type="button" className="button secondary" disabled={data.page + 1 >= data.totalPages} onClick={() => onPage(data.page + 1)}>Next</button></div>;
}
export function Dialog({ title, children, close }: { title: string; children: ReactNode; close: () => void }) {
  const container = useRef<HTMLElement>(null);
  const closeRef = useRef(close);
  closeRef.current = close;
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const focusable = () => Array.from(container.current?.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled])') ?? []);
    const listener = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeRef.current();
      if (event.key === 'Tab') {
        const elements = focusable(); const first = elements[0]; const last = elements.at(-1);
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
      }
    };
    document.addEventListener('keydown', listener);
    const frame = requestAnimationFrame(() => {
      (container.current?.querySelector<HTMLElement>('input, select, textarea') ?? focusable()[0])?.focus();
    });
    return () => { cancelAnimationFrame(frame); document.removeEventListener('keydown', listener); if (previous?.isConnected) previous.focus(); };
  }, []);
  return <div className="dialog-backdrop"><section ref={container} className="dialog" role="dialog" aria-modal="true" aria-label={title}><div className="dialog-heading"><h2>{title}</h2><button type="button" className="icon-button" aria-label="Close dialog" onClick={close}>×</button></div>{children}</section></div>;
}
export function Field({ label, children }: { label: string; children: ReactNode }) {
  const id = useId();
  const help = Children.toArray(children).some(child => isValidElement(child) && child.type === 'small');
  return <div className="field"><label htmlFor={id}>{label}</label>{Children.map(children, child => {
    if (!isValidElement(child)) return child;
    if (child.type === 'input' || child.type === 'select' || child.type === 'textarea') {
      return cloneElement(child as ReactElement<{ id?: string; 'aria-describedby'?: string }>, { id, 'aria-describedby': help ? id + '-help' : undefined });
    }
    if (child.type === 'small') return cloneElement(child as ReactElement<{ id?: string }>, { id: id + '-help' });
    return child;
  })}</div>;
}
export function Badge({ children }: { children: ReactNode }) { return <span className="badge">{children}</span>; }
