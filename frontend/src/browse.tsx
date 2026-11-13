import { useState } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { Icon } from './icons';
import type { IconName } from './icons';
import type { LocationType, Unit } from './types';

/*
 * Browse patterns shared by Inventory, Locations and Home: a remembered grid/list choice, photo-ready visual tiles,
 * and cards. Tiles stay neutral (no accent): teal is reserved for actions and "you are here".
 */

export type BrowseView = 'grid' | 'list';
type Surface = 'items' | 'locations' | 'contents';

function phoneWidth() {
  try { return window.matchMedia('(max-width: 39.99rem)').matches; } catch { return false; }
}

/** Grid on phones and list on desktop until the person picks one; then their choice is remembered per browser. */
export function useBrowseView(surface: Surface): [BrowseView, (view: BrowseView) => void] {
  const key = 'backroom.view.' + surface;
  const [view, setView] = useState<BrowseView>(() => {
    try {
      const saved = localStorage.getItem(key);
      if (saved === 'grid' || saved === 'list') return saved;
    } catch { /* Storage may be unavailable; fall back to the width default. */ }
    return phoneWidth() ? 'grid' : 'list';
  });
  return [view, next => {
    try { localStorage.setItem(key, next); } catch { /* Keep the choice for this visit only. */ }
    setView(next);
  }];
}

export function ViewToggle({ view, onChange, label }: { view: BrowseView; onChange: (view: BrowseView) => void; label: string }) {
  return <div className="view-toggle" role="group" aria-label={label}>
    {(['grid', 'list'] as const).map(option => <button key={option} type="button" className="view-toggle-button" aria-pressed={view === option}
      onClick={() => onChange(option)}><Icon name={option} /><span>{option === 'grid' ? 'Grid' : 'List'}</span></button>)}
  </div>;
}

export function initials(name: string) {
  const words = name.replace(/[^\p{L}\p{N}\s]/gu, ' ').split(/\s+/).filter(Boolean);
  return (words.length > 1 ? words[0]![0]! + words[1]![0]! : (words[0] ?? '?').slice(0, 2)).toUpperCase();
}

export const unitIcons: Record<Unit, IconName> = { EACH: 'box', GRAM: 'weight', KILOGRAM: 'weight', MILLILITER: 'drop', LITER: 'drop' };
export const spaceIcons: Record<LocationType, IconName> = { ROOM: 'location', SHELF: 'shelf', CABINET: 'cabinet', BIN: 'box' };

/** Shrinks a chosen photo to at most 1024 px on its longest side and re-encodes it as JPEG before upload. */
export async function resizePhoto(file: File, side = 1024): Promise<Blob> {
  const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
  const scale = Math.min(1, side / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(bitmap.width * scale));
  canvas.height = Math.max(1, Math.round(bitmap.height * scale));
  const context = canvas.getContext('2d');
  if (!context) throw new Error('This browser can\u2019t prepare photos.');
  context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  return await new Promise<Blob>((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('This photo couldn\u2019t be read.')), 'image/jpeg', 0.82));
}

/** The photo when there is one; otherwise initials and a small kind icon, so layouts stay photo-first. Decorative. */
export function VisualTile({ name, icon, photo, size = 'md' }: { name: string; icon?: IconName; photo?: string | null; size?: 'sm' | 'md' | 'lg' }) {
  return <span className={'visual-tile ' + size + (photo ? ' has-photo' : '')} aria-hidden="true">
    {photo ? <img src={photo} alt="" loading="lazy" decoding="async" />
      : <><span className="visual-initials">{initials(name)}</span>{icon && <Icon name={icon} className="small visual-icon" />}</>}
  </span>;
}

/**
 * A browse card: visual, then the record's link (its accessible name is the record name), facts and badges, then any
 * actions. The card itself is not a link, so action buttons inside it stay separate targets.
 */
export function BrowseCard({ visual, title, meta, badges, actions, selected, muted }: {
  visual: ReactNode; title: ReactNode; meta?: ReactNode; badges?: ReactNode; actions?: ReactNode; selected?: boolean; muted?: boolean;
}) {
  return <li className={'browse-card' + (selected ? ' selected' : '') + (muted ? ' muted' : '')}>
    <div className="browse-card-visual">{visual}</div>
    <div className="browse-card-body">
      <div className="browse-card-title">{title}</div>
      {meta && <div className="browse-card-meta">{meta}</div>}
      {badges && <div className="browse-card-badges">{badges}</div>}
    </div>
    {actions && <div className="browse-card-actions">{actions}</div>}
  </li>;
}

/** Where you are in the physical hierarchy. The last step is the current page; earlier steps are links. */
export function Breadcrumbs({ trail }: { trail: { label: string; to?: string }[] }) {
  return <nav className="breadcrumbs" aria-label="Breadcrumb"><ol>{trail.map((step, index) => <li key={index}>
    {step.to && index < trail.length - 1 ? <Link to={step.to}>{step.label}</Link> : <span aria-current="page">{step.label}</span>}
  </li>)}</ol></nav>;
}

/** "3 locations · 12 items" for a space card; quiet when empty. */
export function spaceFacts(location: { childCount?: number | null; itemCount?: number | null }) {
  const parts = [];
  if (location.childCount) parts.push(location.childCount + (location.childCount === 1 ? ' location' : ' locations'));
  if (location.itemCount) parts.push(location.itemCount + (location.itemCount === 1 ? ' item' : ' items'));
  return parts.length ? parts.join(' · ') : 'Empty';
}
