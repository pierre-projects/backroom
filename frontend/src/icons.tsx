/*
 * Backroom outline icons: 20px box, 1.75 stroke, currentColor.
 * Decorative by default (aria-hidden); always pair with visible text or an aria-label on the control.
 */
const paths = {
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
  copy: <><rect x="7" y="7" width="9.5" height="9.5" rx="1.5" /><path d="M13 7V5a1.5 1.5 0 0 0-1.5-1.5h-6A1.5 1.5 0 0 0 4 5v6.5A1.5 1.5 0 0 0 5.5 13H7" /></>,
} as const;

export type IconName = keyof typeof paths;

export function Icon({ name, className, label }: { name: IconName; className?: string; label?: string }) {
  return <svg className={'icon' + (className ? ' ' + className : '')} viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden={label ? undefined : true} role={label ? 'img' : undefined} aria-label={label} focusable="false">{paths[name]}</svg>;
}
