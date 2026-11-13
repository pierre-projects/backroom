import { useEffect, useState, type ReactNode } from 'react';
import { Link, useLocation } from 'react-router';
import { useAuth } from './auth';
import { Brand } from './components';
import { Icon, type IconName } from './icons';
import { isFilled, site } from './site-config';

const defaultDescription = 'Backroom helps small businesses track what they stock, where it is kept and who changed it, with printable labels you scan from any phone.';

/** Sets the tab title and the description for a public page. */
export function usePageMeta(title: string, description = defaultDescription) {
  useEffect(() => {
    document.title = title;
    document.querySelector('meta[name="description"]')?.setAttribute('content', description);
    document.querySelector('meta[property="og:title"]')?.setAttribute('content', title);
    document.querySelector('meta[property="og:description"]')?.setAttribute('content', description);
    return () => { document.title = 'Backroom'; };
  }, [title, description]);
}

export function ContactEmail() {
  return isFilled(site.contactEmail) ? <a href={'mailto:' + site.contactEmail}>{site.contactEmail}</a> : <span>{site.contactEmail}</span>;
}

/** Links to the legal pages. Shared by the public footer and the sign-in, registration, invitation and reset pages. */
export function LegalLinks() {
  return <nav className="legal-links" aria-label="Legal">
    <Link to="/terms">Terms</Link><Link to="/privacy">Privacy</Link><Link to="/security">Security</Link><Link to="/contact">Contact</Link>
  </nav>;
}

function ServiceStatus() {
  const [connected, setConnected] = useState<boolean | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    void fetch('/actuator/health', { signal: controller.signal }).then(async response => {
      const data = await response.json() as { status?: string };
      if (!controller.signal.aborted) setConnected(response.ok && data.status === 'UP');
    }).catch(() => { if (!controller.signal.aborted) setConnected(false); });
    return () => controller.abort();
  }, []);
  return <span className="status-line" role="status"><span className={'status-dot ' + (connected ? 'connected' : connected === false ? 'unavailable' : '')} aria-hidden="true" />{connected ? 'Service available' : connected === false ? 'Service unavailable — try again shortly' : 'Checking service…'}</span>;
}

const footerColumns: { title: string; links: { label: string; to: string }[] }[] = [
  { title: 'Product', links: [
    { label: 'How it works', to: '/#how-it-works' },
    { label: 'Inventory features', to: '/#features' },
    { label: 'QR labels and barcode scanning', to: '/#scanning' },
    { label: 'Activity history', to: '/#tracking' },
    { label: 'Questions', to: '/#faq' },
  ] },
  { title: 'Who it’s for', links: [
    { label: 'Café inventory', to: '/#who-its-for' },
    { label: 'Bottle shop and liquor store inventory', to: '/#who-its-for' },
    { label: 'Small retail stockrooms', to: '/#who-its-for' },
  ] },
  { title: 'Company', links: [
    { label: 'Security', to: '/security' },
    { label: 'Contact', to: '/contact' },
    { label: 'Terms of Service', to: '/terms' },
    { label: 'Privacy Policy', to: '/privacy' },
  ] },
];

/** Site-wide footer: crawlable text links to every public page and landing section, plus the legal links. */
function SiteFooter({ status }: { status: boolean }) {
  const auth = useAuth();
  return <footer className="site-footer">
    <div className="site-container">
      <Brand />
      <div className="footer-top">
        {footerColumns.map(column => <nav key={column.title} className="footer-column" aria-label={column.title}>
          <h2>{column.title}</h2>
          <ul>{column.links.map(link => <li key={link.label}><Link to={link.to}>{link.label}</Link></li>)}</ul>
        </nav>)}
        <div className="footer-panel">
          <h2>Get started</h2>
          <p>Inventory and stockroom software for small businesses. Track what you stock, where it’s kept and who changed it, with QR labels you scan from any phone.</p>
          {auth.user
            ? <Link className="button primary" to="/app">Your businesses</Link>
            : <div className="footer-actions"><Link className="button primary" to="/register">Create an account</Link><Link className="button footer-secondary" to="/signin">Sign in</Link></div>}
        </div>
      </div>
      <div className="footer-bottom">
        <span>© {new Date().getFullYear()} {isFilled(site.legalEntityName) ? site.legalEntityName : 'Backroom'}. All rights reserved.</span>
        <LegalLinks />
        {status && <ServiceStatus />}
      </div>
    </div>
  </footer>;
}

const headerLinks = [
  { label: 'How it works', to: '/#how-it-works' },
  { label: 'Features', to: '/#features' },
  { label: 'Who it’s for', to: '/#who-its-for' },
  { label: 'Security', to: '/security' },
  { label: 'Questions', to: '/#faq' },
];

/** Public pages: sticky header, full-width bands (landing) or a centered column (legal pages), dark footer. */
export function PublicLayout({ children, status = false, bands = false }: { children: ReactNode; status?: boolean; bands?: boolean }) {
  const auth = useAuth();
  return <div className="site">
    <a className="skip-link" href="#main">Skip to content</a>
    <header className="site-header">
      <div className="site-container header-row">
        <Brand />
        <nav className="header-nav" aria-label="Site">{headerLinks.map(link => <Link key={link.label} to={link.to}>{link.label}</Link>)}</nav>
        <div className="header-actions">{auth.user
          ? <Link className="button primary" to="/app">Your businesses</Link>
          : <><Link className="button header-signin" to="/signin">Sign in</Link><Link className="button primary" to="/register">Get started</Link></>}</div>
      </div>
    </header>
    <main id="main" tabIndex={-1}>{bands ? children : <div className="site-container">{children}</div>}</main>
    <SiteFooter status={status} />
  </div>;
}

function StartButtons({ center = false, secondary = 'link' }: { center?: boolean; secondary?: 'link' | 'button' }) {
  const auth = useAuth();
  return <div className={'landing-actions' + (center ? ' center' : '')}>{auth.user
    ? <Link className="button primary site-cta" to="/app">Open your workspace</Link>
    : <><Link className="button primary site-cta" to="/register">Get started</Link>
      {secondary === 'link'
        ? <Link className="arrow-link" to="/#features">See all features <span aria-hidden="true">›</span></Link>
        : <Link className="button outline site-cta" to="/signin">Sign in</Link>}</>}</div>;
}

interface Showcase { id: string; icon: IconName; eyebrow: string; title: string; points: { icon: IconName; text: string }[]; image: { src: string; width: number; height: number; alt: string } }

const showcases: Showcase[] = [
  { id: 'organizing', icon: 'folder', eyebrow: 'Organizing', title: 'Label every shelf, bin and item.',
    points: [
      { icon: 'folder', text: 'Map your rooms, shelves, cabinets and bins, nested inside each other.' },
      { icon: 'label', text: 'Print QR labels for items and locations on ordinary sticker sheets.' },
      { icon: 'upload', text: 'Bring in your existing item list from a spreadsheet.' },
    ],
    image: { src: '/site/step-label.webp', width: 1200, height: 896, alt: 'A hand pressing a Backroom QR label for espresso beans onto a white storage bin on a café shelf.' } },
  { id: 'scanning', icon: 'scan', eyebrow: 'Scanning', title: 'Scan with any phone. No scanner to buy.',
    points: [
      { icon: 'scan', text: 'Scan labels and existing product barcodes right in the browser; nothing to install.' },
      { icon: 'count', text: 'See what should be on the shelf and how much is recorded there.' },
      { icon: 'alert', text: 'Spot low stock before it runs out, and ask for more on the spot.' },
    ],
    image: { src: '/site/step-scan.webp', width: 2048, height: 1530, alt: 'A hand holding up a phone in a wine shop stockroom, showing the Backroom page for Pinot noir 750 ml with 22 on hand and marked low.' } },
  { id: 'tracking', icon: 'history', eyebrow: 'Tracking', title: 'Every change is on the record.',
    points: [
      { icon: 'history', text: 'Deliveries, counts, usage, waste and transfers save who recorded them, when and why.' },
      { icon: 'undo', text: 'Mistakes are reversed with a new entry, never quietly overwritten.' },
      { icon: 'download', text: 'Export items, stock, movements and requests as CSV files any time.' },
    ],
    image: { src: '/site/step-history.webp', width: 1800, height: 1344, alt: 'A phone on a café counter after closing, showing Backroom’s activity history: stock used, transferred and received, each with the person, item, place and time.' } },
];

const features: { icon: IconName; title: string; body: string }[] = [
  { icon: 'folder', title: 'Locations', body: 'Rooms, shelves, cabinets and bins, nested inside each other, with the items expected in each.' },
  { icon: 'count', title: 'Stock on hand', body: 'Receive, use, waste, transfer and count. Quantities only change through recorded actions, and mistakes are reversed, not overwritten.' },
  { icon: 'label', title: 'Labels and scanning', body: 'Printable QR labels for items and locations. Scan in the browser; nothing to install and no scanner hardware.' },
  { icon: 'alert', title: 'Low stock', body: 'Set a minimum per item and place. Home shows what has fallen below it.' },
  { icon: 'receive', title: 'Restock requests', body: 'Anyone can ask for more of an item at a location. Managers approve or reject it, and whoever unpacks the delivery records it against the request.' },
  { icon: 'sale', title: 'Sales', body: 'Record a sale against the shelf it came from so counts stay current. Backroom keeps no prices, customers or orders.' },
  { icon: 'image', title: 'Photos', body: 'Add a photo to an item so new staff can tell the oat milk from the almond.' },
  { icon: 'upload', title: 'CSV import and export', body: 'Bring in your item list from a spreadsheet. Export items, stock, movements and requests whenever you like.' },
  { icon: 'people', title: 'Staff roles and history', body: 'Owners, managers and employees each see the actions their role allows. One timeline shows who changed what, and when.' },
];

const audiences = ['Cafés', 'Bottle shops', 'Small retail', 'Venues', 'Studios', 'Nonprofits'];

const faqs: { q: string; a: ReactNode }[] = [
  { q: 'What does it cost?', a: isFilled(site.pricing) ? site.pricing : 'Backroom is free while it is in early access. If that changes, we will tell account owners before any charge applies.' },
  { q: 'What do my staff need?', a: 'A phone or tablet with a camera and a current web browser. There is no app to install and no scanner to buy. Scanning needs the site to be opened over HTTPS, which it always is in production.' },
  { q: 'How do I print labels?', a: 'Backroom makes a PDF of labels for the items and locations you choose. Print it on sticker sheets or plain paper with any office printer.' },
  { q: 'How do I get my data in and out?', a: 'Import items from a CSV file, such as a spreadsheet export. Owners and managers can export items, stock, movements and requests as CSV files at any time.' },
  { q: 'What happens when someone leaves the team?', a: 'An owner deactivates their membership in People. They lose access straight away, and the history they recorded keeps their name.' },
  { q: 'What if someone forgets their password?', a: 'An owner makes a one-time reset link from People and passes it on. Backroom does not send email, so every business should have at least two owners.' },
];

export function LandingPage() {
  usePageMeta('Backroom: inventory software for cafés and small businesses');
  const { hash } = useLocation();
  useEffect(() => {
    if (hash) document.getElementById(decodeURIComponent(hash.slice(1)))?.scrollIntoView();
  }, [hash]);
  return <PublicLayout status bands>
    <section className="band band-grey hero" aria-labelledby="hero-title">
      <div className="site-container hero-inner">
        <h1 id="hero-title"><span className="accent">Know what you stock,</span> where it’s kept and who last touched it.</h1>
        <p className="hero-lead">Backroom gives small teams one catalog of items, a map of their rooms, shelves and bins, and labels anyone can scan with a phone to see the stock and update it.</p>
        <StartButtons center />
        <img className="hero-image" src="/site/hero.webp" width="2624" height="1632" fetchPriority="high" alt="A café storeroom with Backroom open on a laptop and a phone. The laptop shows the Home page with two restock requests waiting for review and shelves below their minimum; the phone shows the oat milk item page, 6 on hand and marked low." />
      </div>
    </section>

    <section className="band audience" aria-labelledby="audience-title">
      <div className="site-container">
        <h2 id="audience-title">Made for small teams with <span className="accent">real stock</span> to keep track of.</h2>
        <ul className="audience-list">{audiences.map(name => <li key={name}>{name}</li>)}</ul>
      </div>
    </section>

    <div id="how-it-works">
      <h2 className="sr-only">How it works</h2>
      {showcases.map((row, index) => <section key={row.id} id={row.id} className={'band showcase' + (index % 2 ? ' band-grey reverse' : '')} aria-labelledby={row.id + '-title'}>
        <div className="site-container showcase-inner">
          <div className="showcase-copy">
            <p className="showcase-eyebrow"><span className="eyebrow-icon"><Icon name={row.icon} /></span>{row.eyebrow}</p>
            <h3 id={row.id + '-title'}>{row.title}</h3>
            <ul className="point-list">{row.points.map(point => <li key={point.text}><Icon name={point.icon} />{point.text}</li>)}</ul>
            <StartButtons />
          </div>
          <img className="showcase-image" src={row.image.src} width={row.image.width} height={row.image.height} loading="lazy" alt={row.image.alt} />
        </div>
      </section>)}
    </div>

    <section className="band band-grey" id="features" aria-labelledby="features-title">
      <div className="site-container">
        <div className="section-head">
          <h2 id="features-title">What’s in it</h2>
          <p>Everything a small team needs to keep stock and storage straight, in one place.</p>
        </div>
        <ul className="feature-grid">{features.map(feature => <li key={feature.title}>
          <span className="feature-icon"><Icon name={feature.icon} /></span>
          <h3>{feature.title}</h3>
          <p>{feature.body}</p>
        </li>)}</ul>
      </div>
    </section>

    <section className="band" id="who-its-for" aria-labelledby="who-title">
      <div className="site-container">
        <div className="section-head">
          <h2 id="who-title">Who it’s for</h2>
          <p>Small businesses with physical stock and a few people sharing the job of keeping track of it.</p>
        </div>
        <div className="scenario-grid">
          <article className="scenario">
            <h3>The café</h3>
            <p>The milk order came up short again and nobody is sure whether the last crate went to the front fridge or the back. In Backroom the barista scans the fridge label, sees what is there, and requests more. The manager sees the request before placing the next order and records the delivery when it arrives.</p>
          </article>
          <article className="scenario">
            <h3>The bottle shop</h3>
            <p>A case of gin is somewhere in the stockroom, but which shelf? Scan the bottle’s own barcode to open the item and see every shelf it is kept on. Sales and breakages come off the shelf they happened on, so the weekly count starts close to right.</p>
          </article>
        </div>
      </div>
    </section>

    <section className="band band-grey" aria-labelledby="trust-title">
      <div className="site-container">
        <div className="section-head">
          <h2 id="trust-title">Your records, kept straight</h2>
          <p><Link className="arrow-link" to="/security">How we protect your data <span aria-hidden="true">›</span></Link></p>
        </div>
        <ul className="trust-list">
          <li><span className="feature-icon"><Icon name="download" /></span><h3>Your data is yours</h3><p>Export your items, stock, movements and requests as CSV files at any time, without asking us.</p></li>
          <li><span className="feature-icon"><Icon name="people" /></span><h3>Access checked on the server</h3><p>Every request is checked against the person’s business and role on our server, not just hidden in the app.</p></li>
          <li><span className="feature-icon"><Icon name="history" /></span><h3>History you can trust</h3><p>Stock changes are recorded actions with a person, time and reason. Corrections are new entries, so the past is never quietly rewritten.</p></li>
        </ul>
      </div>
    </section>

    <section className="band" id="faq" aria-labelledby="faq-title">
      <div className="site-container">
        <div className="section-head"><h2 id="faq-title">Questions</h2></div>
        <div className="faq-list">{faqs.map(faq => <details key={faq.q}><summary>{faq.q}</summary><p>{faq.a}</p></details>)}</div>
      </div>
    </section>

    <section className="band band-grey closing-cta" aria-labelledby="cta-title">
      <div className="site-container">
        <h2 id="cta-title">Start with one shelf.</h2>
        <p>Create a business, add a location and a few items, and print your first labels. Bring the rest of your stock in as you go.</p>
        <StartButtons center secondary="button" />
      </div>
    </section>
  </PublicLayout>;
}

export interface LegalSection { title: string; body: ReactNode }

function LegalDocument({ title, summary, sections }: { title: string; summary: ReactNode[]; sections: LegalSection[] }) {
  return <PublicLayout>
    <article className="legal">
      <h1>{title}</h1>
      <p className="legal-updated">Last updated {site.effectiveDate}</p>
      <aside className="legal-summary" aria-labelledby="summary-title">
        <h2 id="summary-title">In short</h2>
        <ul>{summary.map((item, index) => <li key={index}>{item}</li>)}</ul>
        <p className="muted">This summary is for convenience. The full text below is what applies.</p>
      </aside>
      <nav className="legal-toc" aria-label="Contents"><ol>{sections.map((section, index) => <li key={section.title}><a href={'#section-' + (index + 1)}>{section.title}</a></li>)}</ol></nav>
      {sections.map((section, index) => <section key={section.title} id={'section-' + (index + 1)} aria-labelledby={'heading-' + (index + 1)}>
        <h2 id={'heading-' + (index + 1)}>{index + 1}. {section.title}</h2>
        {section.body}
      </section>)}
    </article>
  </PublicLayout>;
}

/*
 * DRAFT FOR LEGAL REVIEW. These Terms were written from the product's actual behavior but have not been
 * reviewed by a lawyer. Fill in every {{PLACEHOLDER}} in site-config.ts and have them reviewed before launch.
 */
const termsSections: LegalSection[] = [
  { title: 'Agreement', body: <>
    <p>These Terms of Service (“Terms”) are an agreement between you and {site.legalEntityName} (“we”, “us”) about your use of Backroom (the “Service”), including this website and the Backroom application.</p>
    <p>By creating an account or using the Service you agree to these Terms and confirm you are at least 16 years old. If you use Backroom for a business, you confirm you are allowed to accept these Terms for it, and “you” includes that business.</p>
  </> },
  { title: 'The Service', body: <>
    <p>Backroom is a web application for recording a business’s items, storage locations, stock quantities and stock movements, and for printing and scanning labels. Features may change as we develop the Service. We will not remove the ability to export your data while your account is open.</p>
  </> },
  { title: 'Accounts and businesses', body: <>
    <p>You need an account to use the application. Give accurate details, keep your password to yourself, and tell us if you think someone else has used your account.</p>
    <p>An account can create a business (an “organization”) and invite others to it. Each person in a business has a role: Owner, Manager or Employee. <strong>Owners are responsible for who they invite, the roles they give, and removing access</strong> when someone leaves. Owners can issue password reset links for their staff; Backroom does not send email, so a business should keep at least two Owners.</p>
  </> },
  { title: 'Acceptable use', body: <>
    <p>You agree not to:</p>
    <ul>
      <li>use the Service for anything unlawful, or to store data you have no right to store;</li>
      <li>try to access another business’s data, another person’s account, or parts of the Service you are not permitted to use;</li>
      <li>probe, scan or test the Service’s security without our written permission, or interfere with its operation, including by overloading it;</li>
      <li>upload malicious code, or content that is unlawful, infringing or abusive;</li>
      <li>resell or provide the Service to others as your own product.</li>
    </ul>
    <p>Item photos and notes should describe stock and storage. Do not upload photos of people or sensitive personal information.</p>
  </> },
  { title: 'Your data', body: <>
    <p>You keep all rights to the information you and your staff enter into Backroom (“Customer Data”), such as item names, locations, quantities, photos and notes. For a business, its Owners control its Customer Data.</p>
    <p>You give us permission to store, process, copy and display Customer Data only as needed to provide, secure, back up and support the Service. We do not sell Customer Data or use it for advertising. Our <Link to="/privacy">Privacy Policy</Link> explains how we handle personal information.</p>
    <p>You are responsible for having the right to enter the data you add, including any personal information about your staff.</p>
  </> },
  { title: 'Accuracy of records', body: <>
    <p>Backroom records the actions your team submits. Recorded quantities are only as accurate as what is recorded: they can drift from what is physically on the shelf through unrecorded use, theft, damage or mistakes. Count your stock regularly. Do not rely on Backroom as your only record for legal, tax, safety, licensing or regulatory purposes.</p>
  </> },
  { title: 'Export and closing your account', body: <>
    <p>Owners and Managers can export a business’s items, stock, movements and requests as CSV files at any time from the application.</p>
    <p>To close your account or delete a business and its data, email us at <ContactEmail /> from the address on the account. We will confirm the request with you, then delete or anonymize the data as described in the <Link to="/privacy">Privacy Policy</Link>. Copies in backups are deleted as those backups expire.</p>
  </> },
  { title: 'Fees', body: isFilled(site.pricing) ? <p>{site.pricing}</p> : <>
    <p>The Service is currently free during early access. If we introduce fees, we will tell account Owners at least 30 days before any charge applies, and you can export your data and stop using the Service before then. We will never charge you without your agreement.</p>
  </> },
  { title: 'Availability and changes', body: <>
    <p>We work to keep Backroom running and your data backed up, but we do not guarantee that the Service will be available at any particular time or free of errors. We may need to take it offline for maintenance or emergencies.</p>
    <p>We may change or discontinue features. If we decide to shut down the Service entirely, we will give at least 30 days’ notice so you can export your data.</p>
  </> },
  { title: 'Suspension and termination', body: <>
    <p>You can stop using the Service at any time. We may suspend or close an account or business that breaks these Terms or puts the Service or other users at risk. Unless the problem is serious or the law prevents it, we will tell you first and give you a chance to export your data.</p>
    <p>Sections 5, 6, 11, 12, 13 and 14 continue to apply after termination.</p>
  </> },
  { title: 'Disclaimers', body: <>
    <p>To the extent the law allows, the Service is provided “as is” and “as available”, without warranties of any kind, express or implied, including warranties of merchantability, fitness for a particular purpose and non-infringement. Some jurisdictions do not allow these exclusions, so parts of this section may not apply to you.</p>
  </> },
  { title: 'Limitation of liability', body: <>
    <p>To the extent the law allows, we are not liable for indirect, incidental, special, consequential or punitive damages, or for lost profits, revenue, stock or data, arising from your use of the Service. Our total liability for any claim relating to the Service is limited to the greater of the amount you paid us for the Service in the 12 months before the claim, or 100 US dollars.</p>
    <p>Nothing in these Terms limits liability that cannot be limited by law, such as liability for fraud or for death or personal injury caused by negligence, or your rights as a consumer under mandatory law.</p>
  </> },
  { title: 'Indemnity', body: <>
    <p>If you use the Service for a business, you will defend and compensate us for claims by third parties arising from Customer Data you entered or from your breach of these Terms, to the extent the law allows.</p>
  </> },
  { title: 'Governing law', body: <>
    <p>These Terms are governed by the laws of {site.governingLaw}, and disputes will be handled by the courts there, except where mandatory consumer law gives you the right to bring a claim where you live.</p>
  </> },
  { title: 'Changes to these Terms', body: <>
    <p>We may update these Terms. For material changes, we will show a notice in the application at least 30 days before they take effect. If you keep using the Service after that, the new Terms apply. If you do not agree, stop using the Service and export your data.</p>
  </> },
  { title: 'Contact', body: <>
    <p>Questions about these Terms: <ContactEmail />.</p>
    {isFilled(site.postalAddress) && <p>{site.legalEntityName}, {site.postalAddress}</p>}
  </> },
];

export function TermsPage() {
  usePageMeta('Terms of Service · Backroom', 'The terms that apply when you use Backroom.');
  return <LegalDocument title="Terms of Service" sections={termsSections} summary={[
    'Your business’s data stays yours, and you can export it as CSV files at any time.',
    'Owners decide who has access to their business and are responsible for removing it.',
    'Backroom records what your team enters. Count your stock regularly; recorded quantities can drift from the shelf.',
    'Backroom is free during early access. We will give 30 days’ notice before any fee or shutdown.',
  ]} />;
}

/*
 * DRAFT FOR LEGAL REVIEW. Every factual statement below was checked against the code and docs as of the
 * last update (see docs/landing-and-legal-brief.md). Re-check it whenever data collection, cookies, hosting
 * or third parties change. Fill in every {{PLACEHOLDER}} in site-config.ts and have it reviewed before launch.
 */
const privacySections: LegalSection[] = [
  { title: 'Who we are', body: <>
    <p>{site.legalEntityName} operates Backroom. This policy explains what personal information Backroom handles, why, and the choices you have. Contact us at <ContactEmail />.</p>
    <p>Backroom is used by businesses. For the stock and storage records a business keeps in Backroom, including what its staff record, that business decides what to collect and why, and we handle the information on its behalf. For your account itself (your name, email and password), and for running and securing the Service, we are responsible.</p>
  </> },
  { title: 'What we collect', body: <>
    <h3>Your account</h3>
    <ul>
      <li><strong>Name and email address</strong>, which you give us when you register. Your name is shown to other people in businesses you belong to.</li>
      <li><strong>Password</strong>, stored only as a salted one-way hash. We cannot read it.</li>
    </ul>
    <h3>Business membership</h3>
    <ul>
      <li>The businesses you belong to, your role in each, and whether your access is active.</li>
      <li>Invitations and password reset links. We store only a fingerprint (hash) of each link, with when it was created, by whom, and when it expires or was used.</li>
    </ul>
    <h3>Business records</h3>
    <ul>
      <li>What businesses enter: business name, locations, items (names, SKUs, barcodes, units, notes), stock quantities and movements, low-stock minimums, restock requests and deliveries, recorded sales (which hold no prices or customer details), and items imported from CSV files.</li>
      <li><strong>Item photos.</strong> Your browser shrinks each photo and saves it as a new JPEG before upload, which removes embedded details such as camera location.</li>
      <li><strong>Activity history.</strong> Each change records the name and account of the person who made it, the time, and what changed. This is how a business knows who recorded what.</li>
    </ul>
    <h3>Technical information</h3>
    <ul>
      <li>Our application logs record technical details of requests, such as a request id, the operation, the business it applied to and errors. They do not record names, emails, notes, quantities, passwords, session ids or link tokens.</li>
      <li>Our hosting provider may log network information, such as IP addresses, to run and protect its infrastructure.</li>
    </ul>
    <p>We do not use analytics, advertising or tracking tools, and we do not collect payment details.</p>
  </> },
  { title: 'Why we use it', body: <>
    <ul>
      <li>To provide the Service: sign you in, show you the businesses you belong to, and store and display your business’s records.</li>
      <li>To keep it secure: check access on every request, prevent abuse, and investigate errors.</li>
      <li>To keep your data safe: make and test backups.</li>
      <li>To answer you when you contact us.</li>
    </ul>
    <p>We do not sell personal information, share it for advertising, or use it to build profiles.</p>
  </> },
  { title: 'Legal bases (EEA and UK)', body: <>
    <p>If data protection law such as the GDPR applies to you, we rely on:</p>
    <ul>
      <li><strong>Contract</strong>, to provide the account and Service you asked for;</li>
      <li><strong>Legitimate interests</strong>, to secure the Service, keep activity history that businesses rely on, keep backups and fix problems;</li>
      <li><strong>Legal obligation</strong>, where the law requires us to keep or disclose information.</li>
    </ul>
    <p>For business records, the business using Backroom is responsible for its own legal basis, including for information about its staff.</p>
  </> },
  { title: 'Cookies and browser storage', body: <>
    <p>Backroom uses one cookie: a <strong>session cookie</strong> that keeps you signed in. It cannot be read by scripts on the page, is only sent over HTTPS in production, and expires after 30 minutes without activity or when you sign out. The protection against cross-site request forgery is tied to this session on our server and needs no extra cookie.</p>
    <p>The application also uses your browser’s local storage for display preferences, such as grid or list view, and to remember that you downloaded a label sheet. These stay on your device and are not sent to us.</p>
    <p>Because we only use what is strictly necessary for the Service to work, we do not show a cookie banner.</p>
  </> },
  { title: 'Camera', body: <>
    <p>When you scan a label, Backroom asks your browser for permission to use the camera. Codes are recognized on your device. Camera images are not uploaded, recorded or stored; only the code that was read is used to open the right item or location. The camera stops when you leave the scan screen. Photos you deliberately add to an item are uploaded as described above.</p>
  </> },
  { title: 'Who we share it with', body: <>
    <ul>
      <li><strong>People in your business.</strong> Others in a business you belong to see your name on the activity you recorded there. Its Owners also see your email address and role.</li>
      <li><strong>Our hosting provider</strong>, {site.hostingProvider}, which stores the application and database for us in {site.hostingRegion}, under a contract that limits its use of the data.</li>
      <li><strong>Authorities</strong>, if the law requires it, or to protect the rights and safety of our users or the public.</li>
      <li><strong>A successor</strong>, if the Service is sold or transferred. We will tell you before your information becomes subject to a different policy.</li>
    </ul>
    <p>We use no other third-party services. The application loads no external fonts, scripts or embeds.</p>
  </> },
  { title: 'International transfers', body: <>
    <p>Your information is stored in {site.hostingRegion}. If that is outside your country, we use the safeguards the law requires, such as standard contractual clauses, for the transfer.</p>
  </> },
  { title: 'How long we keep it', body: <>
    <ul>
      <li><strong>Accounts</strong> are kept while they are open. When you close your account, we delete or anonymize your name and email. Activity history kept by businesses you belonged to remains part of their records, attributed to an anonymized name.</li>
      <li><strong>Business records</strong> are kept while the business uses Backroom, and deleted when its Owners ask us to delete the business.</li>
      <li><strong>Deactivated staff.</strong> When an Owner removes someone from a business, their access ends but the history they recorded keeps their name, so the business’s records stay accurate.</li>
      <li><strong>Backups</strong> are kept for 14 days, then deleted. Deleted information disappears from backups when they expire.</li>
      <li><strong>Invitation and reset links</strong> expire on their own; the records of them are kept with the business’s history.</li>
    </ul>
  </> },
  { title: 'How we protect it', body: <>
    <p>Passwords are hashed, connections are encrypted with HTTPS, and every request is checked against the person’s business and role on our server. Our <Link to="/security">security page</Link> has more detail. No system is perfectly secure; if a breach affects your information, we will tell you and the authorities as the law requires.</p>
  </> },
  { title: 'Your rights', body: <>
    <p>Depending on where you live, you may have the right to access, correct, export or delete your personal information, to object to or restrict how we use it, and to complain to your data protection authority.</p>
    <ul>
      <li>You can see your own name and email in the application.</li>
      <li>Owners and Managers can export a business’s records as CSV files at any time.</li>
      <li>For anything else, including closing your account, email <ContactEmail /> from the address on your account. We will reply within 30 days.</li>
    </ul>
    <p>If your request is about a business’s records, such as activity attributed to you, we may refer you to that business, because it decides what its records hold.</p>
  </> },
  { title: 'California residents', body: <>
    <p>If the California Consumer Privacy Act applies, you have the rights to know, delete and correct personal information, and not to be discriminated against for using them. The categories we collect are identifiers (name, email) and the business records and technical information described in section 2, for the purposes in section 3. We do not sell or share personal information for cross-context behavioral advertising. To make a request, email <ContactEmail />.</p>
  </> },
  { title: 'Children', body: <>
    <p>Backroom is a tool for businesses and is not meant for anyone under 16. We do not knowingly collect information from children. If you think a child has created an account, contact us and we will delete it.</p>
  </> },
  { title: 'Changes to this policy', body: <>
    <p>We will update this policy when what we collect or how we use it changes, and show the new date at the top. For material changes, we will show a notice in the application before they take effect.</p>
  </> },
  { title: 'Contact', body: <>
    <p>Privacy questions and requests: <ContactEmail />.</p>
    {isFilled(site.postalAddress) && <p>{site.legalEntityName}, {site.postalAddress}</p>}
  </> },
];

export function PrivacyPage() {
  usePageMeta('Privacy Policy · Backroom', 'What personal information Backroom collects, why, and your choices.');
  return <LegalDocument title="Privacy Policy" sections={privacySections} summary={[
    'We collect your name, email and a hashed password, plus the stock records your business enters.',
    'One session cookie keeps you signed in. No analytics, advertising or trackers.',
    'Scanning reads codes on your phone; camera images are never uploaded.',
    'We do not sell your information. Our hosting provider is the only other company that handles it.',
    'Email us to close your account or delete a business’s data.',
  ]} />;
}

export function SecurityPage() {
  usePageMeta('Security · Backroom', 'How Backroom protects your account and your business records.');
  const points: { icon: IconName; title: string; body: ReactNode }[] = [
    { icon: 'people', title: 'Access checked on every request', body: 'Each request is checked on our server against the business it touches and the person’s role in it. Hiding a button is never the only protection. Removed staff lose access immediately, even on devices that are still signed in.' },
    { icon: 'user', title: 'Passwords', body: 'Passwords must be at least 12 characters and are stored only as salted one-way hashes. Password reset links are issued by an Owner, work once, and expire after 24 hours.' },
    { icon: 'link', title: 'Sessions and links', body: 'Your session cookie cannot be read by page scripts, is sent only over HTTPS, and expires after 30 minutes of inactivity. Forms are protected against cross-site request forgery. Invitation and reset links are stored only as fingerprints, so a database copy does not reveal them.' },
    { icon: 'history', title: 'A durable record', body: 'Stock changes are recorded actions with a person, time and reason, written in the same transaction as the change. If the record cannot be written, the change does not happen.' },
    { icon: 'download', title: 'Backups', body: 'The database is backed up daily, and backups are kept for 14 days. We regularly restore a backup into a separate database and check it, because a backup only counts once it has been restored.' },
    { icon: 'alert', title: 'Minimal logs, no trackers', body: 'Our logs leave out names, emails, notes, quantities, passwords, sessions and link tokens. The site uses no analytics, advertising or third-party scripts.' },
  ];
  return <PublicLayout>
    <article className="legal">
      <h1>Security</h1>
      <p className="landing-lead">How we protect your account and your business’s records.</p>
      <ul className="trust-list security-list">{points.map(point => <li key={point.title}><Icon name={point.icon} /><div><h2>{point.title}</h2><p>{point.body}</p></div></li>)}</ul>
      <section className="legal-contact" aria-labelledby="report-title">
        <h2 id="report-title">Report a problem</h2>
        <p>If you think you have found a security issue, email <ContactEmail /> with the details. Please don’t test against other people’s businesses or data.</p>
      </section>
    </article>
  </PublicLayout>;
}

export function ContactPage() {
  usePageMeta('Contact · Backroom', 'How to reach the people behind Backroom.');
  return <PublicLayout>
    <article className="legal">
      <h1>Contact</h1>
      <p className="landing-lead">Questions, problems, privacy requests or feedback: email us and a person will reply.</p>
      <dl className="contact-list">
        <div><dt>Email</dt><dd><ContactEmail /></dd></div>
        {isFilled(site.postalAddress) && <div><dt>Post</dt><dd>{site.legalEntityName}<br />{site.postalAddress}</dd></div>}
      </dl>
      <section className="legal-contact" aria-labelledby="help-title">
        <h2 id="help-title">Before you write</h2>
        <ul>
          <li><strong>Forgot your password?</strong> Ask an Owner of your business for a reset link from People. We can’t reset it by email.</li>
          <li><strong>Need access to a business?</strong> Ask one of its Owners to invite you.</li>
          <li><strong>Closing your account or deleting a business?</strong> Write from the email address on the account so we can confirm it’s you.</li>
        </ul>
      </section>
    </article>
  </PublicLayout>;
}
