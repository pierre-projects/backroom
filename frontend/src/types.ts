export type Role = 'OWNER' | 'MANAGER' | 'EMPLOYEE';
export type Unit = 'EACH' | 'GRAM' | 'KILOGRAM' | 'MILLILITER' | 'LITER';
export type LocationType = 'ROOM' | 'SHELF' | 'CABINET' | 'BIN';
export interface User { id: string; email: string; displayName: string }
export interface Organization { id: string; name: string; role: Role; version: number }
export interface Item { id: string; name: string; sku: string | null; unit: Unit; archived: boolean; version: number; onHand: string }
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
export const unitShort: Record<Unit, string> = { EACH: '', GRAM: 'g', KILOGRAM: 'kg', MILLILITER: 'mL', LITER: 'L' };
export type StockOperationType = 'OPENING' | 'RECEIPT' | 'USAGE' | 'WASTE' | 'TRANSFER' | 'COUNT' | 'REVERSAL';
export interface StockBalance { itemId: string; locationId: string; locationName: string; parentName: string | null; quantity: string; version: number; hasHistory: boolean }
export interface StockMovement {
  id: string; operationId: string; operationType: StockOperationType; itemId: string; locationId: string; locationName: string; parentName: string | null;
  quantityChange: string; balanceAfter: string; actorName: string; occurredAt: string; reference: string | null; note: string | null;
  reversesOperationId: string | null; reversed: boolean;
}
export interface StockOperation {
  id: string; type: StockOperationType; itemId: string; actorName: string; occurredAt: string; reversesOperationId: string | null;
  movements: StockMovement[]; count: { countedQuantity: string; recordedQuantity: string; difference: string } | null; replayed: boolean;
}
export type TargetType = 'ITEM' | 'LOCATION';
export type ScanStatus = 'ITEM' | 'LOCATION' | 'REVOKED' | 'OTHER_ORGANIZATION' | 'NOT_FOUND' | 'NOT_REGISTERED' | 'UNSUPPORTED';
export interface ResourceCode {
  id: string; kind: 'GENERATED' | 'MANUFACTURER'; display: string; url: string | null; targetType: TargetType; targetId: string;
  status: 'ACTIVE' | 'REVOKED'; createdAt: string; version: number;
}
export interface ScanResult {
  status: ScanStatus; targetType: TargetType | null; id: string | null; name: string | null; parentName: string | null; sku: string | null;
  unit: Unit | null; archived: boolean; code: string | null; organizationId: string | null; organizationName: string | null; canRegister: boolean;
}
export interface CodeLink { status: ScanStatus; organizationId: string; targetType: TargetType | null; id: string | null }
export interface LabelSettings { publicBaseUrl: string; localAddress: boolean; maxLabels: number }
export type RequestStatus = 'SUBMITTED' | 'APPROVED' | 'PARTIALLY_RECEIVED' | 'FULFILLED' | 'REJECTED' | 'CANCELLED' | 'CLOSED_SHORT';
export interface RestockReceipt { id: string; quantity: string; receiverName: string; receivedAt: string; stockOperationId: string; reversed: boolean }
export interface RestockRequest {
  id: string; number: number; label: string; status: RequestStatus;
  itemId: string; itemName: string; sku: string | null; unit: Unit; itemArchived: boolean;
  locationId: string; locationName: string; parentName: string | null;
  requested: string; approved: string | null; received: string; remaining: string;
  requesterId: string; requesterName: string; note: string | null; createdAt: string;
  decidedByName: string | null; decidedAt: string | null; decisionNote: string | null;
  closedByName: string | null; closedAt: string | null; closeReason: string | null;
  updatedAt: string; version: number; receipts: RestockReceipt[]; replayed: boolean;
}
export interface RequestSummary { needsReview: number; toReceive: number }
