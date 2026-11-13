import { useEffect } from 'react';
import type { MouseEvent, ReactNode } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import type { Item, ScanResult } from './types';

interface ScanView { scanned?: { value: string; format?: string }; result?: ScanResult; matches?: Item[] }
interface Position { target: string; y: number }
interface Origin {
  url: string; label: string; index: number; key: string; scope: string;
  previous?: Origin; position: Position; scan?: ScanView;
}
interface Entry { origin?: Origin; position?: Position; scan?: ScanView; scope?: string }
const storageKey = 'backroom.navigation';
let memoryScope = '';
export function clearWorkspaceNavigation() {
  memoryScope = '';
  try { sessionStorage.removeItem(storageKey); } catch { /* Private browsing. */ }
}
/**
 * The tab's navigation scope for this business. Reading is safe during render: a new scope is created in memory
 * only once per business and the same value is returned until it is saved, so renders stay consistent.
 */
function scope(org: string) {
  let value = memoryScope;
  try { value = sessionStorage.getItem(storageKey) ?? value; } catch { /* Use memory. */ }
  if (!value.startsWith(org + '/')) value = memoryScope.startsWith(org + '/') ? memoryScope : org + '/' + crypto.randomUUID();
  memoryScope = value;
  return value;
}
/** Saves the scope for this tab, so origins survive a reload. Runs after render. */
function persistScope(value: string) {
  try { if (sessionStorage.getItem(storageKey) !== value) sessionStorage.setItem(storageKey, value); } catch { /* Use memory. */ }
}
function remember(entry: Entry) {
  const state = window.history.state;
  window.history.replaceState({ ...state, usr: { ...state?.usr, ...entry } }, '');
}
function ordinary(event: MouseEvent) {
  return event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey;
}
export function useRecordNavigation() {
  const current = useLocation();
  const navigate = useNavigate();
  const { orgId = '' } = useParams();
  const token = scope(orgId);
  useEffect(() => persistScope(token), [token]);
  const entry = (current.state ?? {}) as Entry;
  const candidate = entry.origin;
  const root = '/app/organizations/' + orgId + '/';
  const path = candidate?.url.split(/[?#]/)[0] ?? '';
  const valid = path.startsWith(root) && /^(home|scan|search|items|locations|requests)(\/[a-zA-Z0-9-]+)?$/.test(path.slice(root.length));
  const origin = candidate?.scope === token && valid ? candidate : undefined;

  function visit(to: string, label: string, target: string) {
    const position = { target, y: window.scrollY };
    remember({ scope: token, position });
    const origin: Origin = { url: current.pathname + current.search + current.hash, label,
      index: window.history.state.idx, key: current.key, scope: token, position,
      previous: candidate?.scope === token ? candidate : undefined, scan: window.history.state.usr?.scan };
    navigate(to, { state: { origin, scope: token } });
  }
  function back(fallback: { to: string; label: string }) {
    if (!origin) return fallback;
    return { to: origin.url, label: 'Back to ' + origin.label, onClick: (event: MouseEvent<HTMLAnchorElement>) => {
      if (!ordinary(event)) return;
      event.preventDefault();
      // The origin is the entry right behind this one: plain Back keeps Forward working.
      if (window.history.state.idx === origin.index + 1) navigate(-1);
      else navigate(origin.url, { replace: true, state: { scope: token, origin: origin.previous, position: origin.position, scan: origin.scan } });
    } };
  }
  return { visit, back, current, restoredScan: entry.scope === token ? entry.scan : undefined,
    rememberScan: (scan?: ScanView) => remember({ scan, scope: token }) };
}

export function RecordLink({ to, from, children, className }: { to: string; from: string; children: ReactNode; className?: string }) {
  const nav = useRecordNavigation();
  return <Link className={className} to={to} data-return-target={to} onClick={event => {
    if (!ordinary(event)) return;
    event.preventDefault(); nav.visit(to, from, to);
  }}>{children}</Link>;
}

/** Records load asynchronously. Restore after the row mounts, or focus the heading if it disappeared. */
export function useWorkspacePosition(org: string) {
  const loc = useLocation();
  useEffect(() => {
    const entry = (loc.state ?? {}) as Entry;
    const token = scope(org);
    persistScope(token);
    const position = entry.scope === token ? entry.position : undefined;
    const historyAnchor = loc.hash === '#history-heading';
    if (!position && !historyAnchor) { window.scrollTo(0, 0); return; }
    const restore = () => {
      const main = document.getElementById('main');
      const target = historyAnchor ? document.getElementById('history-heading') :
        main?.querySelector<HTMLElement>('[data-return-target="' + CSS.escape(position!.target) + '"]');
      if (!target) return false;
      target.focus({ preventScroll: true });
      if (historyAnchor) target.scrollIntoView({ block: 'start' });
      else window.scrollTo(0, position!.y);
      return true;
    };
    if (restore()) return;
    const observer = new MutationObserver(() => { if (restore()) stop(); });
    observer.observe(document.body, { subtree: true, childList: true });
    const timeout = window.setTimeout(() => {
      stop();
      const heading = document.querySelector<HTMLElement>('main h1');
      if (heading && (document.activeElement === document.body || document.activeElement === null)) {
        heading.tabIndex = -1; heading.focus();
      }
    }, 5000);
    function stop() { observer.disconnect(); window.clearTimeout(timeout); }
    return stop;
  }, [loc.key, loc.hash, org]);
}
