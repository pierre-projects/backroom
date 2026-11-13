import { useState } from 'react';
import type { FormEvent } from 'react';
import { send } from './api';
import { Badge, Dialog, ErrorMessage, Field, useAll } from './components';
import { Icon } from './icons';
import type { IconName } from './icons';
import type { Item, StockBalance, StockMovement, StockOperation, StockOperationType, StorageLocation, Unit } from './types';
import { unitShort } from './types';

/** "2.5 kg", "+3", "−0.25 L". Quantities arrive as exact decimal strings from the API. */
export function formatQuantity(value: string, unit: Unit, signed = false) {
  const number = Number(value);
  const text = Math.abs(number).toLocaleString(undefined, { maximumFractionDigits: 3 });
  const sign = number < 0 ? '−' : signed && number > 0 ? '+' : '';
  return sign + text + (unitShort[unit] ? ' ' + unitShort[unit] : '');
}
export function locationPath(slot: { locationName: string; parentName: string | null }) {
  return slot.parentName ? slot.parentName + ' › ' + slot.locationName : slot.locationName;
}

/** Active leaf locations (where stock can be kept), labelled with their parent so duplicate names are distinguishable. */
export function useStockLocations(base: string) {
  const all = useAll<StorageLocation>(base + '/locations');
  const byId = new Map((all.data ?? []).map(location => [location.id, location]));
  const label = (location: StorageLocation) => {
    const parent = location.parentId ? byId.get(location.parentId) : undefined;
    return parent ? parent.name + ' › ' + location.name : location.name;
  };
  const leaves = all.data?.filter(location => !location.archived && !all.data!.some(child => child.parentId === location.id && !child.archived))
    .map(location => ({ id: location.id, label: label(location) }))
    .sort((a, b) => a.label.localeCompare(b.label));
  return { leaves, error: all.error, reload: all.reload, label: (id: string) => { const found = byId.get(id); return found ? label(found) : ''; } };
}

export type StockAction = 'receive' | 'usage' | 'waste' | 'transfer' | 'count' | 'opening';
const titles: Record<StockAction, string> = {
  receive: 'Receive stock', usage: 'Record usage', waste: 'Record waste', transfer: 'Transfer stock', count: 'Count stock', opening: 'Set starting quantity',
};
const clean = (value: FormDataEntryValue | null) => String(value ?? '').trim().replace(',', '.');

/**
 * One sheet for every stock command. The operation key is created when the sheet opens and reused on every retry,
 * so a request that timed out can be sent again without counting the stock twice.
 */
export function StockDialog({ action, item, base, balances, locationId, close, done }: {
  action: StockAction; item: Item; base: string; balances: StockBalance[]; locationId?: string;
  close: () => void; done: (message: string) => void;
}) {
  const [operationKey] = useState(() => crypto.randomUUID());
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>();
  const locations = useStockLocations(base);
  const stocked = balances.filter(balance => Number(balance.quantity) > 0);
  const removal = action === 'usage' || action === 'waste';
  const known = stocked.find(balance => balance.locationId === locationId)
    ?? (!locationId && stocked.length === 1 ? stocked[0] : undefined);
  const [from, setFrom] = useState(known?.locationId ?? (removal ? '' : stocked[0]?.locationId ?? ''));
  const slot = balances.find(balance => balance.locationId === locationId);
  const unitLabel = unitShort[item.unit] ? ' (' + unitShort[item.unit] + ')' : '';
  const available = (id: string) => balances.find(balance => balance.locationId === id)?.quantity ?? '0';
  const pathOf = (id: string) => { const found = balances.find(balance => balance.locationId === id); return found ? locationPath(found) : locations.label(id); };

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const quantity = clean(form.get('quantity'));
    const note = String(form.get('note') ?? '').trim() || null;
    const target = String(form.get('locationId') ?? locationId ?? '');
    const amount = formatQuantity(quantity, item.unit);
    setPending(true); setError(undefined);
    try {
      if (action === 'receive') {
        await send<StockOperation>(base + '/inventory/receipts', 'POST', { operationKey, itemId: item.id, locationId: target, quantity, note, reference: String(form.get('reference') ?? '').trim() || null });
        done('Received ' + amount + ' at ' + pathOf(target));
      } else if (action === 'opening') {
        await send<StockOperation>(base + '/inventory/opening-balances', 'POST', { operationKey, itemId: item.id, locationId: target, quantity, note });
        done('Starting quantity at ' + pathOf(target) + ' set to ' + amount);
      } else if (action === 'usage' || action === 'waste') {
        const operation = await send<StockOperation>(base + '/inventory/removals', 'POST', { operationKey, itemId: item.id, locationId: target, quantity, note, reason: action === 'waste' ? 'WASTE' : 'USAGE' });
        const movement = operation.movements.find(entry => entry.locationId === target);
        const balanceText = movement ? '. ' + formatQuantity(movement.balanceAfter, item.unit) + ' on hand there.' : '';
        done((action === 'waste' ? 'Recorded ' + amount + ' of waste at ' : 'Used ' + amount + ' from ') + pathOf(target) + balanceText);
      } else if (action === 'transfer') {
        const to = String(form.get('toLocationId'));
        await send<StockOperation>(base + '/inventory/transfers', 'POST', { operationKey, itemId: item.id, fromLocationId: from, toLocationId: to, quantity, note });
        done('Moved ' + amount + ' from ' + pathOf(from) + ' to ' + pathOf(to));
      } else if (action === 'count' && slot) {
        const result = await send<StockOperation>(base + '/inventory/counts', 'POST', { operationKey, itemId: item.id, locationId: slot.locationId, countedQuantity: quantity, expectedBalanceVersion: slot.version, note });
        const difference = result.count?.difference ?? '0';
        done('Counted ' + amount + ' at ' + locationPath(slot) + (Number(difference) === 0 ? '. It matched.' : '. Adjusted by ' + formatQuantity(difference, item.unit, true) + '.'));
      }
    } catch (value) { setError(value); } finally { setPending(false); }
  }

  const quantityField = (label: string, hint?: string) => <Field label={label + unitLabel}>
    <input name="quantity" data-autofocus={removal && known ? true : undefined} inputMode={item.unit === 'EACH' ? 'numeric' : 'decimal'} autoComplete="off" required
      pattern={item.unit === 'EACH' ? '[0-9]+' : '[0-9]+([.,][0-9]{1,3})?'} title={item.unit === 'EACH' ? 'Whole numbers only' : 'Up to three decimal places'} />
    {hint && <small>{hint}</small>}
  </Field>;
  const destinationChoices = (name: string, label: string, exclude?: string) => <Field label={label}>
    <select key={locations.leaves ? 'ready' : 'loading'} name={name} required defaultValue={locationId && locationId !== exclude ? locationId : ''}>
      <option value="">Choose a location</option>
      {locations.leaves?.filter(choice => choice.id !== exclude).map(choice => <option key={choice.id} value={choice.id}>{choice.label}</option>)}
    </select>
  </Field>;
  const noteField = <Field label="Note (optional)"><input name="note" maxLength={500} autoComplete="off" /></Field>;
  const noStock = (action === 'usage' || action === 'waste' || action === 'transfer') && stocked.length === 0;
  const noLocations = (action === 'receive' || action === 'opening' || action === 'transfer') && locations.leaves?.length === 0;

  return <Dialog title={titles[action]} close={close}>
    <p className="dialog-subject">{item.name}</p>
    <ErrorMessage error={error ?? locations.error} />
    {noStock ? <p className="muted">There's no stock of this item yet. Receive some first.</p>
      : noLocations ? <p className="muted">Create a shelf, cabinet or bin in Locations first.</p>
      : <form onSubmit={submit}>
        {(action === 'receive' || action === 'opening') && destinationChoices('locationId', 'Location')}
        {removal && (known ? <div className="count-summary"><span>From <strong>{locationPath(known)}</strong></span>
          <span>{formatQuantity(known.quantity, item.unit)} available</span><input type="hidden" name="locationId" value={known.locationId} /></div>
          : <Field label="From"><select name="locationId" required value={from} onChange={event => setFrom(event.target.value)}>
            <option value="">Choose a shelf</option>
            {stocked.map(balance => <option key={balance.locationId} value={balance.locationId}>{locationPath(balance)} · {formatQuantity(balance.quantity, item.unit)}</option>)}
          </select></Field>)}
        {action === 'transfer' && <>
          <Field label="From"><select name="fromLocationId" required value={from} onChange={event => setFrom(event.target.value)}>
            {stocked.map(balance => <option key={balance.locationId} value={balance.locationId}>{locationPath(balance)} · {formatQuantity(balance.quantity, item.unit)}</option>)}
          </select></Field>
          {destinationChoices('toLocationId', 'To', from)}
        </>}
        {action === 'count' && slot && <div className="count-summary"><span>{locationPath(slot)}</span><span>Recorded <strong className="numeric">{formatQuantity(slot.quantity, item.unit)}</strong></span></div>}
        {action === 'count' ? quantityField('Counted quantity', 'Enter what is physically there, including zero.')
          : action === 'transfer' ? quantityField('Quantity', 'Available at the source: ' + formatQuantity(available(from), item.unit))
          : quantityField('Quantity')}
        {action === 'receive' && <Field label="Reference (optional)"><input name="reference" maxLength={120} autoComplete="off" /><small>Delivery note, invoice or supplier.</small></Field>}
        {noteField}
        <div className="form-actions"><button type="button" className="button secondary" onClick={close}>Cancel</button>
          <button className="button primary" disabled={pending || !locations.leaves}>{pending ? 'Saving…' : titles[action]}</button></div>
      </form>}
  </Dialog>;
}

export function StockRowActions({ quantity, subject, open }: {
  quantity: string; subject: string; open: (action?: 'usage' | 'receive') => void;
}) {
  const stocked = Number(quantity) > 0;
  return <div className="stock-row-actions">
    <button type="button" className="button secondary compact raise" onClick={() => open(stocked ? 'usage' : 'receive')}>
      {stocked ? 'Record usage' : 'Receive stock'}<span className="sr-only"> {subject}</span></button>
    <button type="button" className="text-button raise" onClick={() => open()}>More actions<span className="sr-only"> {subject}</span></button>
  </div>;
}

/** Choices for one location row. Employees record usage, waste and receipts; managers also transfer, count and set starting stock. */
/** What is missing to reach the minimum, computed in thousandths so decimals stay exact. */
export function shortfall(slot: { quantity: string; minimum: string | null }) {
  if (!slot.minimum) return null;
  const missing = Math.round(Number(slot.minimum) * 1000) - Math.round(Number(slot.quantity) * 1000);
  return missing > 0 ? String(missing / 1000) : null;
}

/** "Min 4 kg" with a Low badge when the slot is under it. */
export function MinimumNote({ slot, unit }: { slot: StockBalance; unit: Unit }) {
  if (!slot.minimum) return null;
  return <span className="minimum-note">{slot.low && <Badge tone="warning">Low</Badge>}<span className="numeric">Min {formatQuantity(slot.minimum, unit)}</span></span>;
}

export function MinimumDialog({ slot, item, base, close, done }: { slot: StockBalance; item: Item; base: string; close: () => void; done: (message: string) => void }) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>();
  const unitLabel = unitShort[item.unit] ? ' (' + unitShort[item.unit] + ')' : '';
  async function save(minimum: string | null) {
    setPending(true); setError(undefined);
    try {
      await send(base + '/items/' + item.id + '/placements/' + slot.locationId + '/minimum', 'PUT', { minimum });
      done(minimum ? 'Minimum at ' + locationPath(slot) + ' set to ' + formatQuantity(minimum, item.unit) : 'Minimum at ' + locationPath(slot) + ' removed');
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  return <Dialog title="Set a minimum" close={close}>
    <p className="dialog-subject">{item.name} · {locationPath(slot)}</p>
    <form onSubmit={event => { event.preventDefault(); void save(clean(new FormData(event.currentTarget).get('minimum'))); }}>
      <ErrorMessage error={error} />
      <Field label={'Keep at least' + unitLabel}>
        <input name="minimum" inputMode={item.unit === 'EACH' ? 'numeric' : 'decimal'} autoComplete="off" required defaultValue={slot.minimum ?? ''}
          pattern={item.unit === 'EACH' ? '[0-9]+' : '[0-9]+([.,][0-9]{1,3})?'} />
        <small className="field-help">Below this, the item shows as low here. There is {formatQuantity(slot.quantity, item.unit)} now.</small>
      </Field>
      <div className="form-actions">
        {slot.minimum && <button type="button" className="button ghost" disabled={pending} onClick={() => void save(null)}>Remove minimum</button>}
        <button type="button" className="button secondary" onClick={close}>Cancel</button>
        <button className="button primary" disabled={pending}>{pending ? 'Saving…' : 'Save minimum'}</button>
      </div>
    </form>
  </Dialog>;
}

export function SlotSheet({ slot, item, canManage, close, pick, remove, request, setMinimum }: {
  slot: StockBalance; item: Item; canManage: boolean; close: () => void; pick: (action: StockAction) => void; remove?: () => void; request?: () => void; setMinimum?: () => void;
}) {
  const hasStock = Number(slot.quantity) > 0;
  const option = (icon: IconName, label: string, action: StockAction, hint?: string) =>
    <button type="button" className="menu-item sheet-option" onClick={() => pick(action)}><Icon name={icon} /><span>{label}{hint && <small>{hint}</small>}</span></button>;
  return <Dialog title={locationPath(slot)} close={close}>
    <p className="dialog-subject">{item.name} · <span className="numeric">{formatQuantity(slot.quantity, item.unit)}</span> on hand{slot.minimum && <> · <span className="numeric">min {formatQuantity(slot.minimum, item.unit)}</span></>}</p>
    <div className="sheet-options">
      {hasStock && option('use', 'Record usage', 'usage')}
      {hasStock && option('waste', 'Record waste', 'waste', 'Spoiled, broken or expired')}
      {option('receive', 'Receive stock here', 'receive')}
      {request && <button type="button" className="menu-item sheet-option" onClick={request}><Icon name="plus" /><span>Request more here<small>{slot.low ? 'Prefilled with what is missing' : 'Ask a manager to restock this location'}</small></span></button>}
      {canManage && hasStock && option('transfer', 'Transfer from here', 'transfer')}
      {canManage && option('count', 'Count', 'count', 'Record what is physically there')}
      {canManage && !slot.hasHistory && option('start', 'Set starting quantity', 'opening')}
      {canManage && setMinimum && <button type="button" className="menu-item sheet-option" onClick={setMinimum}><Icon name="alert" /><span>{slot.minimum ? 'Change minimum' : 'Set minimum'}<small>Show this item as low below a quantity</small></span></button>}
      {canManage && !hasStock && remove && <button type="button" className="menu-item sheet-option danger" onClick={remove}><Icon name="close" /><span>Remove this location from the item</span></button>}
    </div>
  </Dialog>;
}

const movementTitles: Record<StockOperationType, string> = {
  OPENING: 'Starting quantity', RECEIPT: 'Received', USAGE: 'Used', WASTE: 'Wasted', TRANSFER: 'Moved', COUNT: 'Count adjustment', REVERSAL: 'Reversal',
};
const movementIcons: Record<StockOperationType, IconName> = {
  OPENING: 'start', RECEIPT: 'receive', USAGE: 'use', WASTE: 'waste', TRANSFER: 'transfer', COUNT: 'count', REVERSAL: 'undo',
};
const operationNouns: Record<StockOperationType, string> = {
  OPENING: 'starting quantity', RECEIPT: 'receipt', USAGE: 'usage', WASTE: 'waste', TRANSFER: 'transfer', COUNT: 'count', REVERSAL: 'reversal',
};
export function movementTitle(movement: StockMovement) {
  if (movement.operationType === 'TRANSFER') return Number(movement.quantityChange) < 0 ? 'Moved out' : 'Moved in';
  return movementTitles[movement.operationType];
}

export function MovementList({ movements, unit, canReverse, reverse }: {
  movements: StockMovement[]; unit: Unit; canReverse: boolean; reverse: (movement: StockMovement) => void;
}) {
  return <ul className="movement-list">{movements.map(movement => {
    // A transfer has two rows; offer the reversal once, on the outgoing side.
    const reversible = canReverse && !movement.reversed && movement.operationType !== 'COUNT' && movement.operationType !== 'REVERSAL'
      && (movement.operationType !== 'TRANSFER' || Number(movement.quantityChange) < 0);
    const change = Number(movement.quantityChange);
    return <li key={movement.id}>
      <span className="activity-icon" aria-hidden="true"><Icon name={movementIcons[movement.operationType]} /></span>
      <div className="movement-main">
        <div className="movement-title"><strong>{movementTitle(movement)}</strong>{movement.reversed && <Badge>Reversed</Badge>}</div>
        <p>{locationPath(movement)} · {movement.actorName}</p>
        {(movement.reference || movement.note) && <p className="movement-note">{[movement.reference, movement.note].filter(Boolean).join(' · ')}</p>}
      </div>
      <div className="movement-amount">
        <span className={'numeric ' + (change < 0 ? 'negative' : 'positive')}>{formatQuantity(movement.quantityChange, unit, true)}</span>
        <span className="movement-after numeric">→ {formatQuantity(movement.balanceAfter, unit)}</span>
        <time dateTime={movement.occurredAt}>{new Date(movement.occurredAt).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}</time>
      </div>
      {reversible && <div className="movement-actions"><button type="button" className="text-button" onClick={() => reverse(movement)}><Icon name="undo" className="small" />Reverse</button></div>}
    </li>;
  })}</ul>;
}

export function ReverseDialog({ movement, unit, base, close, done }: {
  movement: StockMovement; unit: Unit; base: string; close: () => void; done: (message: string) => void;
}) {
  const [operationKey] = useState(() => crypto.randomUUID());
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>();
  const what = operationNouns[movement.operationType];
  const amount = formatQuantity(String(Math.abs(Number(movement.quantityChange))), unit);
  async function confirm() {
    setPending(true); setError(undefined);
    try {
      await send<StockOperation>(base + '/inventory/operations/' + movement.operationId + '/reverse', 'POST', { operationKey, note: null });
      done('Reversed the ' + what + ' of ' + amount);
    } catch (value) { setError(value); } finally { setPending(false); }
  }
  return <Dialog title={'Reverse this ' + what + ' of ' + amount + '?'} close={close}>
    <p>Backroom records the opposite movement, so the history shows both. This only works if the stock is still where it was.</p>
    <ErrorMessage error={error} />
    <div className="form-actions"><button type="button" className="button secondary" data-autofocus onClick={close}>Cancel</button>
      <button type="button" className="button primary" disabled={pending} onClick={() => void confirm()}>{pending ? 'Reversing…' : 'Reverse'}</button></div>
  </Dialog>;
}
