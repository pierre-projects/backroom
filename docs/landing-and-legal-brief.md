# Landing site and legal pages: agent brief

Copy everything below the line into a fresh agent session at the repository root.

---

You are building Backroom's public marketing site and legal pages: the landing page, Terms of Service, Privacy Policy, and the small supporting pages around them. Work inside this repository. Read before you write.

## Read first

1. `README.md` and `backroom_prd_v1.md`: what Backroom is, who it serves (small businesses; café and liquor-store reference workflows), and the core promise: *scan something → understand it → take action → leave a trustworthy history*.
2. `docs/phase-1.md` through `docs/phase-7.md`: what actually ships today (organizations, roles, nested locations, catalog, stock operations, QR labels and scanning, restock requests and deliveries, low stock, search, CSV export, item photos, sales, CSV import).
3. `docs/design-system.md`, `docs/design-walkthrough.html`, `docs/frontend-codebook.md`, and `frontend/src/styles.css`: tokens, type, components, and voice. Use the tokens; never hard-code hex values.
4. `docs/operations.md`: hosting status, backups and retention, and what logs never contain.
5. `frontend/src/App.tsx`: the existing `/` route renders `<Landing />`. Find it and replace or extend it; don't build a second site.

## What to build

All pages are public routes in the existing React app (same Vite build, same router). No new framework and no CMS.

| Route | Page |
| --- | --- |
| `/` | Landing page |
| `/terms` | Terms of Service |
| `/privacy` | Privacy Policy |
| `/security` | Short page on how data is protected (optional; build it only if the facts below support it) |
| `/contact` | Contact details, or a `mailto:` link in the footer if that's enough |

Add a shared public header (logo, **Sign in**, **Get started**) and footer (Terms, Privacy, Contact, ©year) used by every public page, including `/signin` and `/register`. The registration form must link to Terms and Privacy ("By creating an account you agree to the Terms of Service and Privacy Policy") without a pre-ticked checkbox.

### Landing page

Audience: owners and managers of small businesses with physical stock (cafés, bottle shops, small retail, venues, studios, nonprofits) who currently use a spreadsheet, a clipboard, or memory.

Sections, in order:

1. **Hero.** One-line value proposition built on "Everything behind the business," a one-sentence explanation, primary **Get started** (→ `/register`) and secondary **Sign in**. Show a real screenshot of the app (capture it from the running dev server with seeded data, phone and desktop), not an illustration.
2. **How it works.** Three steps: label your shelves and items → scan with any phone → every change is recorded with who, when, and why.
3. **Features.** Only features that ship today, per the phase docs: locations and placements, stock counts and movements, printable QR labels and scanning, restock requests and deliveries, low stock, search, photos, CSV import and export, staff roles and invitations, activity history. Each gets a short heading and one or two plain sentences.
4. **Who it's for.** Café and bottle-shop examples, written as concrete scenarios ("The milk order is short again…"), not personas.
5. **Trust.** Your data is yours (CSV export), role-based access enforced on the server, full activity history. Only claims the code backs up.
6. **FAQ.** 5 to 7 questions: cost, devices needed (any phone with a camera; no scanner hardware), label printing, getting data in and out, what happens when someone leaves the team, password recovery (there is no email; see below).
7. **Closing call to action** and footer.

Voice: follow the design system. Plain, sentence case, specific. No hype words ("revolutionary," "seamless," "AI-powered"), no fake testimonials, logos, user counts, or star ratings. Marketing pages may be warmer than in-app copy, but every claim must be true today.

Design: same coral tokens and system font stack as the app, so moving from the site to the app feels like one product. Mobile first, WCAG 2.2 AA, 44 px touch targets, visible focus, real `<h1>`–`<h3>` hierarchy, alt text on every screenshot. No third-party fonts, trackers, analytics, chat widgets, or embeds. Set `<title>`, meta description, Open Graph tags, and a favicon per page.

### Terms of Service and Privacy Policy

Write them as clear, plain-language documents with numbered headings, a "Last updated" date, and a short summary box at the top of each. They are **drafts for legal review**: put a visible-in-source comment at the top of each saying so, and list them under "Needs owner/lawyer review" in your final report.

**Ground every statement in the code.** Before writing the Privacy Policy, audit the repository and record what you find:

- What personal data is collected: account fields (display name, email, password hash), organization and staff membership, activity history attributed to people, item photos, notes, and whatever else the entities and Flyway migrations in `src/main/resources/db/migration` show.
- Cookies and browser storage: the session cookie and CSRF token (`application.yaml`, `application-prod.yaml`), and any `localStorage`/`sessionStorage` use in `frontend/src`. If the only cookies are strictly necessary, say so and skip the cookie banner.
- Camera: scanning uses the device camera in the browser. Confirm in `scan.tsx` whether frames ever leave the device; state it accurately.
- Email: the app sends none (password resets are owner-issued links, per `docs/phase-5.md`). Don't promise email notifications or email-based recovery.
- Logs: `docs/operations.md` says names, emails, notes, quantities, passwords, session ids, and tokens are never logged. Verify in the logging config before relying on it.
- Third parties: confirm there are no analytics, payment, email, or storage providers in `pom.xml`, `package.json`, or the code. Hosting isn't chosen yet (`docs/operations.md`), so describe the hosting provider as a placeholder.
- Retention and deletion: backups keep 14 daily dumps. Check whether users can delete their account and whether owners can delete an organization or remove members, and describe exactly what happens to their records and to activity history attributed to them. If deletion isn't implemented, say requests go through the contact address; don't invent a self-serve flow.
- Data roles: the business (organization owner) controls its stock data and its staff's records; Backroom processes it on the business's behalf. Each person controls their own account.

Privacy Policy sections: who we are; what we collect and why; legal bases (GDPR) and a California/CCPA notice, both written to apply only if relevant; cookies; camera; sharing and subprocessors; international transfers; retention; security; your rights and how to exercise them; children (not for under-16s); changes; contact.

Terms of Service sections: acceptance; the service; accounts and the organization owner's responsibility for their staff; acceptable use; customer data ownership and the licence Backroom needs to run the service; export and account closure; fees (free while in early access unless told otherwise); availability with no uptime guarantee; recorded quantities are only as accurate as what staff record (the PRD's own caveat); disclaimers; limitation of liability; indemnity; termination; governing law; changes; contact.

### Placeholders: don't invent these

Use these exact tokens and list every occurrence in your final report. Don't guess values.

- `{{LEGAL_ENTITY_NAME}}`: the person or company operating Backroom
- `{{CONTACT_EMAIL}}`: support and privacy contact
- `{{POSTAL_ADDRESS}}`: if required by the chosen jurisdiction
- `{{GOVERNING_LAW}}`: jurisdiction and venue
- `{{HOSTING_PROVIDER}}` and `{{HOSTING_REGION}}`
- `{{EFFECTIVE_DATE}}`
- `{{PRICING}}`: if pricing isn't "free during early access"

Keep them in one module (for example `frontend/src/site-config.ts`) so they're filled in once.

## Verify

- `npm --prefix frontend run build` and the existing lint/type checks pass.
- Add Playwright coverage in `frontend/e2e`: each public route renders its `<h1>`; header and footer links resolve; the register page links to Terms and Privacy; signed-in users still reach `/app`.
- Run `./scripts/verify.ps1`.
- Check every page at 375 px and 1280 px in the browser preview, and run an accessibility pass (axe or Lighthouse) on each. Fix anything below AA.
- Re-read both legal documents against your audit notes. Every factual claim must trace to code or docs; delete any that don't.

## Final report

1. Routes and files added or changed.
2. The data audit: what's collected, where it's stored, cookies and storage, third parties.
3. Every placeholder and where it appears.
4. Claims you left out because the code didn't support them.
5. "Needs owner/lawyer review": the Terms, the Privacy Policy, and any jurisdiction-specific questions.
6. Screenshots of the landing page on phone and desktop.

Don't commit or push unless asked.
