/*
 * Backroom outline icons: 20px box, 1.75 stroke, currentColor.
 * Decorative by default (aria-hidden); always pair with visible text or an aria-label on the control.
 */
const paths = {
  home: <><path d="M3.5 9 10 3.5 16.5 9" /><path d="M5 8v8.5h10V8M8.5 16.5v-4.5h3v4.5" /></>,
  upload: <><path d="M10 13V4.5M6 8.5l4-4 4 4" /><path d="M3.5 13.5v2a1 1 0 0 0 1 1h11a1 1 0 0 0 1-1v-2" /></>,
  grid: <><rect x="3.5" y="3.5" width="5.5" height="5.5" rx="1" /><rect x="11" y="3.5" width="5.5" height="5.5" rx="1" /><rect x="3.5" y="11" width="5.5" height="5.5" rx="1" /><rect x="11" y="11" width="5.5" height="5.5" rx="1" /></>,
  list: <><path d="M7.5 5.5h9M7.5 10h9M7.5 14.5h9" /><path d="M3.5 5.5h.01M3.5 10h.01M3.5 14.5h.01" /></>,
  shelf: <><path d="M3.5 3.5v13M16.5 3.5v13M3.5 7h13M3.5 12h13" /><path d="M6 12V9.5h3V12M11 7V5h2.5v2" /></>,
  cabinet: <><rect x="4" y="3" width="12" height="14" rx="1.5" /><path d="M10 3v14M8 9.5v1M12 9.5v1" /></>,
  drop: <path d="M10 3.5c2.6 3.3 4.5 5.9 4.5 8.2a4.5 4.5 0 0 1-9 0c0-2.3 1.9-4.9 4.5-8.2z" />,
  weight: <><path d="M7.5 6.5a2.5 2.5 0 1 1 5 0" /><path d="M5.5 6.5h9l1.5 10h-12z" /></>,
  sale: <><path d="M3.5 10.2V4.5a1 1 0 0 1 1-1h5.7l6.3 6.3-6.7 6.7z" /><circle cx="7.2" cy="7.2" r="1" /></>,
  camera: <><path d="M3.5 7a1.5 1.5 0 0 1 1.5-1.5h2L8.3 4h3.4L13 5.5h2A1.5 1.5 0 0 1 16.5 7v7.5A1.5 1.5 0 0 1 15 16H5a1.5 1.5 0 0 1-1.5-1.5z" /><circle cx="10" cy="10.5" r="2.75" /></>,
  download: <><path d="M10 3.5v9M6 9l4 4 4-4" /><path d="M4 16.5h12" /></>,
  catalog: <><path d="M3.5 6.5 10 3l6.5 3.5v7L10 17l-6.5-3.5z" /><path d="M3.5 6.5 10 10l6.5-3.5M10 10v7" /></>,
  location: <><path d="M3 17V7.5L10 3l7 4.5V17" /><path d="M6.5 17v-6h7v6M6.5 14h7" /></>,
  people: <><circle cx="7.5" cy="7" r="2.75" /><path d="M2.5 16.5c.6-2.7 2.5-4 5-4s4.4 1.3 5 4" /><path d="M13 4.5a2.6 2.6 0 0 1 0 5M14.5 12.7c1.6.5 2.6 1.7 3 3.8" /></>,
  activity: <><circle cx="10" cy="10" r="7" /><path d="M10 6v4l2.5 2" /></>,
  settings: <><circle cx="10" cy="10" r="2.5" /><path d="M10 2.5v2M10 15.5v2M17.5 10h-2M4.5 10h-2M15.3 4.7l-1.4 1.4M6.1 13.9l-1.4 1.4M15.3 15.3l-1.4-1.4M6.1 6.1 4.7 4.7" /></>,
  search: <><circle cx="9" cy="9" r="5.5" /><path d="m13 13 4 4" /></>,
  plus: <path d="M10 4.5v11M4.5 10h11" />,
  chevronRight: <path d="m8 5 5 5-5 5" />,
  chevronLeft: <path d="m12 5-5 5 5 5" />,
  chevronDown: <path d="m6 8 4 4 4-4" />,
  switch: <path d="M6 7.5h10l-3-3M14 12.5H4l3 3" />,
  user: <><circle cx="10" cy="7" r="3" /><path d="M4 17c.8-3 3-4.5 6-4.5s5.2 1.5 6 4.5" /></>,
  logout: <><path d="M8 3.5H4.5v13H8" /><path d="M12 6.5 15.5 10 12 13.5M15.5 10H8" /></>,
  close: <path d="m5.5 5.5 9 9M14.5 5.5l-9 9" />,
  check: <path d="m4.5 10.5 3.5 3.5 7.5-8" />,
  alert: <><circle cx="10" cy="10" r="7" /><path d="M10 6.5v4M10 13.5v.01" /></>,
  box: <><rect x="3" y="4" width="14" height="12" rx="1.5" /><path d="M3 8h14M8 11h4" /></>,
  link: <><path d="M8.5 11.5a3 3 0 0 0 4.2 0l2.6-2.6a3 3 0 0 0-4.2-4.2l-.8.8" /><path d="M11.5 8.5a3 3 0 0 0-4.2 0l-2.6 2.6a3 3 0 0 0 4.2 4.2l.8-.8" /></>,
  receive: <><path d="M10 3.5v9M6 9l4 4 4-4" /><path d="M3.5 13.5v2a1 1 0 0 0 1 1h11a1 1 0 0 0 1-1v-2" /></>,
  use: <><path d="M4.5 10h11" /><circle cx="10" cy="10" r="7" /></>,
  waste: <><path d="M4 6h12M8 6V4.5h4V6M5.5 6l.8 10h7.4l.8-10" /><path d="M8.5 9v4.5M11.5 9v4.5" /></>,
  transfer: <path d="M4 7h11l-3-3M16 13H5l3 3" />,
  count: <><rect x="4.5" y="3.5" width="11" height="13.5" rx="1.5" /><path d="M7.5 3.5v2h5v-2M7.5 10l1.5 1.5 3.5-3.5M7.5 14h5" /></>,
  undo: <><path d="M7 5 3.5 8.5 7 12" /><path d="M3.5 8.5h8a4.5 4.5 0 0 1 0 9H8" /></>,
  start: <><path d="M5 17V3.5" /><path d="M5 4h9l-2 3 2 3H5" /></>,
  scan: <><path d="M3.5 7V4.5a1 1 0 0 1 1-1H7M13 3.5h2.5a1 1 0 0 1 1 1V7M16.5 13v2.5a1 1 0 0 1-1 1H13M7 16.5H4.5a1 1 0 0 1-1-1V13" /><path d="M6.5 10h7" /></>,
  label: <><path d="M3.5 4.5h7.8l5.2 5.5-5.2 5.5H3.5z" /><circle cx="7" cy="10" r="1.2" /></>,
  folder: <path d="M3 6a1.5 1.5 0 0 1 1.5-1.5h3.3l1.7 2h6A1.5 1.5 0 0 1 17 8v6.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 3 14.5z" />,
  bell: <><path d="M5.5 13.5V9a4.5 4.5 0 0 1 9 0v4.5l1.5 1.5H4z" /><path d="M8.5 17h3" /></>,
  history: <><path d="M3.5 10a6.5 6.5 0 1 0 1.9-4.6" /><path d="M3.5 3.5v3h3M10 6.5V10l2.5 1.5" /></>,
  image: <><rect x="3.5" y="4" width="13" height="12" rx="1.5" /><circle cx="7.5" cy="8" r="1.25" /><path d="m3.5 14 4-4 3 3 2-2 4 4" /></>,
  minus: <path d="M4.5 10h11" />,
  sort: <><path d="M6.5 4v12M3.5 7l3-3 3 3" /><path d="M13.5 16V4M10.5 13l3 3 3-3" /></>,
  copy: <><rect x="7" y="7" width="9.5" height="9.5" rx="1.5" /><path d="M13 7V5a1.5 1.5 0 0 0-1.5-1.5h-6A1.5 1.5 0 0 0 4 5v6.5A1.5 1.5 0 0 0 5.5 13H7" /></>,
} as const;

export type IconName = keyof typeof paths;

export function Icon({ name, className, label }: { name: IconName; className?: string; label?: string }) {
  return <svg className={'icon' + (className ? ' ' + className : '')} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden={label ? undefined : true} role={label ? 'img' : undefined} aria-label={label} focusable="false">{paths[name]}</svg>;
}
