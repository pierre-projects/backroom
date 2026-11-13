/*
 * Details the public site and legal pages need from the business operating Backroom.
 * Each value is a {{PLACEHOLDER}} until the owner fills it in. Pages show unfilled values
 * as-is, so a missing detail is visible instead of silently invented.
 */
export const site = {
  /** The person or company operating Backroom. */
  legalEntityName: '{{LEGAL_ENTITY_NAME}}',
  /** Support and privacy contact. */
  contactEmail: '{{CONTACT_EMAIL}}',
  /** Postal address, if the governing jurisdiction requires one. */
  postalAddress: '{{POSTAL_ADDRESS}}',
  /** Jurisdiction and venue for the Terms of Service. */
  governingLaw: '{{GOVERNING_LAW}}',
  /** Hosting provider and region for the application and database. */
  hostingProvider: '{{HOSTING_PROVIDER}}',
  hostingRegion: '{{HOSTING_REGION}}',
  /** Date both legal documents take effect, also shown as "Last updated". */
  effectiveDate: '{{EFFECTIVE_DATE}}',
  /** Leave as a placeholder while Backroom is free during early access. */
  pricing: '{{PRICING}}',
} as const;

export function isFilled(value: string) {
  return !/^\{\{[A-Z_]+\}\}$/.test(value);
}
