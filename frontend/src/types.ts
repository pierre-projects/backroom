export type Role = 'OWNER' | 'MANAGER' | 'EMPLOYEE';
export type Unit = 'EACH' | 'GRAM' | 'KILOGRAM' | 'MILLILITER' | 'LITER';
export type LocationType = 'ROOM' | 'SHELF' | 'CABINET' | 'BIN';
export interface User { id: string; email: string; displayName: string }
export interface Organization { id: string; name: string; role: Role; version: number }
export interface Item { id: string; name: string; sku: string | null; unit: Unit; archived: boolean; version: number }
export interface StorageLocation { id: string; name: string; type: LocationType; parentId: string | null; archived: boolean; version: number }
export interface Placement { id: string; itemId: string; locationId: string; locationName: string }
export interface Member { id: string; userId: string; displayName: string; email: string; role: Role; active: boolean }
export interface Invitation { id: string; role: Role; expiresAt: string; status: string; acceptedAt: string | null }
export interface CreatedInvitation { invitation: Invitation; invitePath: string }
export interface InvitationPreview { organizationId: string; organizationName: string; role: Role; expiresAt: string }
export interface Activity { id: string; actorId: string; actorName: string; action: string; resourceType: string; resourceId: string; occurredAt: string; metadata: Record<string, string> }
export interface Page<T> { items: T[]; page: number; size: number; totalElements: number; totalPages: number }
export const unitNames: Record<Unit, string> = { EACH: 'Each', GRAM: 'Gram', KILOGRAM: 'Kilogram', MILLILITER: 'Milliliter', LITER: 'Liter' };
export const roleNames: Record<Role, string> = { OWNER: 'Owner', MANAGER: 'Manager', EMPLOYEE: 'Employee' };
