package com.backroom.restocking;

import static com.backroom.access.Role.EMPLOYEE;
import static com.backroom.access.Role.MANAGER;
import static com.backroom.access.Role.OWNER;
import static com.backroom.restocking.RequestStatus.OPEN;
import static com.backroom.restocking.RequestStatus.RECEIVING;
import static com.backroom.restocking.RequestStatus.SUBMITTED;

import com.backroom.access.AccessPolicy;
import com.backroom.access.Role;
import com.backroom.audit.AuditService;
import com.backroom.inventory.ItemQueries;
import com.backroom.inventory.Quantities;
import com.backroom.inventory.StockDtos;
import com.backroom.inventory.StockService;
import com.backroom.locations.LocationQueries;
import com.backroom.shared.error.ConflictException;
import com.backroom.shared.error.ForbiddenException;
import com.backroom.shared.error.NotFoundException;
import com.backroom.shared.web.PageResponse;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal restock requests. Approval is coordination only; a delivery changes stock solely by calling
 * inventory's StockService inside this transaction, so the receipt, movement, balance and request state
 * commit or roll back together. Lock order for a delivery: request row, then (inside StockService) item,
 * location, placement and balance.
 */
@Service
@Transactional
public class RestockService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RestockService.class);
    private final RestockRequestRepository requests;
    private final RestockReceiptRepository receipts;
    private final StockService stock;
    private final ItemQueries items;
    private final LocationQueries locations;
    private final AccessPolicy access;
    private final AuditService audit;

    RestockService(
            RestockRequestRepository requests,
            RestockReceiptRepository receipts,
            StockService stock,
            ItemQueries items,
            LocationQueries locations,
            AccessPolicy access,
            AuditService audit) {
        this.requests = requests;
        this.receipts = receipts;
        this.stock = stock;
        this.items = items;
        this.locations = locations;
        this.access = access;
        this.audit = audit;
    }

    // ---------- Commands ----------

    public RestockDtos.RequestView submit(UUID orgId, RestockDtos.Submit in) {
        access.lock(orgId); // any active member; also serializes request numbers
        var item = items.require(orgId, in.itemId());
        if (item.archived()) throw new ConflictException("ARCHIVED", "Archived items can't be requested.");
        locations.requireActiveLeaf(orgId, in.locationId());
        var quantity = Quantities.positive(item.unit(), in.quantity());
        var actor = access.actor();
        var request = requests.saveAndFlush(new RestockRequest(
                orgId,
                requests.maxNumber(orgId) + 1,
                item.id(),
                in.locationId(),
                quantity,
                actor.userId(),
                actor.displayName(),
                blankToNull(in.note())));
        record(orgId, "RESTOCK_SUBMITTED", request, "quantity", Quantities.text(quantity));
        return view(request, List.of(), false);
    }

    public RestockDtos.RequestView approve(UUID orgId, UUID id, RestockDtos.Approve in) {
        access.require(orgId, OWNER, MANAGER);
        var request = lockForDecision(orgId, id, in.expectedVersion());
        if (request.getStatus() != SUBMITTED) throw invalid("Only submitted requests can be approved.");
        var item = items.require(orgId, request.getItemId());
        var approved = in.approvedQuantity() == null
                ? request.getRequestedQuantity()
                : Quantities.positive(item.unit(), in.approvedQuantity());
        var actor = access.actor();
        request.approve(approved, actor.userId(), actor.displayName(), blankToNull(in.note()));
        touch(request);
        record(
                orgId,
                "RESTOCK_APPROVED",
                request,
                "quantity",
                Quantities.text(approved),
                "requested",
                Quantities.text(request.getRequestedQuantity()));
        return detail(orgId, request, false);
    }

    public RestockDtos.RequestView reject(UUID orgId, UUID id, RestockDtos.Reason in) {
        access.require(orgId, OWNER, MANAGER);
        var request = lockForDecision(orgId, id, in.expectedVersion());
        if (request.getStatus() != SUBMITTED) throw invalid("Only submitted requests can be rejected.");
        var actor = access.actor();
        request.reject(actor.userId(), actor.displayName(), in.reason().strip());
        touch(request);
        record(orgId, "RESTOCK_REJECTED", request, "reason", request.getDecisionNote());
        return detail(orgId, request, false);
    }

    public RestockDtos.RequestView cancel(UUID orgId, UUID id, RestockDtos.Version in) {
        var role = access.require(orgId);
        var request = lockForDecision(orgId, id, in.expectedVersion());
        var actor = access.actor();
        if (role == EMPLOYEE && !request.getRequesterId().equals(actor.userId())) throw new ForbiddenException();
        if (request.getStatus() != SUBMITTED)
            throw invalid("Only requests that haven't been reviewed can be cancelled.");
        request.cancel(actor.userId(), actor.displayName());
        touch(request);
        record(orgId, "RESTOCK_CANCELLED", request);
        return detail(orgId, request, false);
    }

    public RestockDtos.RequestView closeShort(UUID orgId, UUID id, RestockDtos.Reason in) {
        access.require(orgId, OWNER, MANAGER);
        var request = lockForDecision(orgId, id, in.expectedVersion());
        if (!RECEIVING.contains(request.getStatus()))
            throw invalid("Only approved requests that are still waiting for stock can be closed short.");
        var actor = access.actor();
        request.closeShort(actor.userId(), actor.displayName(), in.reason().strip());
        touch(request);
        record(
                orgId,
                "RESTOCK_CLOSED_SHORT",
                request,
                "received",
                Quantities.text(request.getReceivedQuantity()),
                "reason",
                request.getCloseReason());
        return detail(orgId, request, false);
    }

    /**
     * Records a delivery. The operation key makes retries safe: it is checked after the request lock is held,
     * so a duplicate that waited behind the original sees the original's receipt and replays it.
     */
    public RestockDtos.RequestView receive(UUID orgId, UUID id, RestockDtos.Receive in) {
        access.require(orgId);
        var request = requests.lockById(orgId, id).orElseThrow(NotFoundException::new);
        var item = items.require(orgId, request.getItemId());
        var quantity = Quantities.positive(item.unit(), in.quantity());
        var actor = access.actor();

        var earlier = stock.operationIdForKey(orgId, in.operationKey());
        if (earlier.isPresent()) {
            var receipt = receipts.findByOrganizationIdAndStockOperationId(orgId, earlier.get());
            if (receipt.isPresent()
                    && receipt.get().getRequestId().equals(id)
                    && receipt.get().getReceiverId().equals(actor.userId())
                    && receipt.get().getQuantity().compareTo(quantity) == 0) {
                log.info(
                        "Restock delivery replayed: type=RESTOCK_RECEIPT operationKey={} organizationId={}",
                        in.operationKey(),
                        orgId);
                return detail(orgId, request, true);
            }
            throw new ConflictException(
                    "IDEMPOTENCY_KEY_REUSED", "This operation key was already used for a different request.");
        }
        if (request.getStatus() == SUBMITTED)
            throw new ConflictException("NOT_APPROVED", "This request hasn't been approved yet.");
        if (!RECEIVING.contains(request.getStatus()))
            throw invalid("This request is closed. Record extra stock as an ordinary receipt.");
        if (quantity.compareTo(request.remaining()) > 0) {
            throw new ConflictException(
                    "OVER_RECEIPT",
                    "Only " + Quantities.text(request.remaining())
                            + " is still expected on this request. Record extra stock as an ordinary receipt.");
        }

        var operation = stock.receive(
                orgId,
                new StockDtos.Receipt(
                        in.operationKey(),
                        request.getItemId(),
                        request.getLocationId(),
                        quantity,
                        label(request),
                        blankToNull(in.note())));
        receipts.save(new RestockReceipt(
                orgId, request.getId(), operation.id(), quantity, actor.userId(), actor.displayName()));
        request.recordDelivery(quantity);
        touch(request);
        log.info(
                "Restock delivery recorded: type=RESTOCK_RECEIPT operationKey={} organizationId={}",
                in.operationKey(),
                orgId);
        record(
                orgId,
                "RESTOCK_RECEIVED",
                request,
                "quantity",
                Quantities.text(quantity),
                "received",
                Quantities.text(request.getReceivedQuantity()));
        return detail(orgId, request, false);
    }

    // ---------- Reads ----------

    @Transactional(readOnly = true)
    public PageResponse<RestockDtos.RequestView> list(
            UUID orgId, RestockDtos.Filter filter, UUID itemId, UUID locationId, int page, int size) {
        access.require(orgId);
        var statuses = switch (filter) {
            case NEEDS_REVIEW -> EnumSet.of(SUBMITTED);
            case TO_RECEIVE -> EnumSet.copyOf(RECEIVING);
            case OPEN, MINE ->
                filter == RestockDtos.Filter.MINE ? EnumSet.allOf(RequestStatus.class) : EnumSet.copyOf(OPEN);
            case ALL -> EnumSet.allOf(RequestStatus.class);
        };
        var requester = filter == RestockDtos.Filter.MINE ? access.actor().userId() : null;
        var result = requests.search(
                orgId,
                statuses,
                requester,
                itemId,
                locationId,
                PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        var itemSummaries = items.summaries(
                orgId, result.stream().map(request -> request.getItemId()).toList());
        var locationSummaries = locations.summaries(
                orgId, result.stream().map(request -> request.getLocationId()).toList());
        return PageResponse.from(result.map(request -> view(
                request,
                List.of(),
                false,
                itemSummaries.get(request.getItemId()),
                locationSummaries.get(request.getLocationId()))));
    }

    @Transactional(readOnly = true)
    public RestockDtos.RequestView get(UUID orgId, UUID id) {
        access.require(orgId);
        return detail(orgId, requests.findByOrganizationIdAndId(orgId, id).orElseThrow(NotFoundException::new), false);
    }

    @Transactional(readOnly = true)
    public RestockDtos.Summary summary(UUID orgId) {
        var role = access.require(orgId);
        long review =
                role == Role.EMPLOYEE ? 0 : requests.countByOrganizationIdAndStatusIn(orgId, EnumSet.of(SUBMITTED));
        return new RestockDtos.Summary(review, requests.countByOrganizationIdAndStatusIn(orgId, RECEIVING));
    }

    // ---------- Helpers ----------

    private RestockRequest lockForDecision(UUID orgId, UUID id, Long expectedVersion) {
        var request = requests.lockById(orgId, id).orElseThrow(NotFoundException::new);
        request.checkVersion(expectedVersion);
        return request;
    }

    private static ConflictException invalid(String message) {
        return new ConflictException("INVALID_TRANSITION", message);
    }

    private void touch(RestockRequest request) {
        request.markUpdated();
        requests.flush();
    }

    static String label(RestockRequest request) {
        return "R-" + request.getNumber();
    }

    private RestockDtos.RequestView detail(UUID orgId, RestockRequest request, boolean replayed) {
        var list = receipts.findByOrganizationIdAndRequestIdOrderByCreatedAtAscIdAsc(orgId, request.getId());
        var reversed = stock.reversedAmong(
                orgId,
                list.stream().map(receipt -> receipt.getStockOperationId()).toList());
        var views = list.stream()
                .map(receipt -> new RestockDtos.ReceiptView(
                        receipt.getId(),
                        Quantities.text(receipt.getQuantity()),
                        receipt.getReceiverName(),
                        receipt.getCreatedAt(),
                        receipt.getStockOperationId(),
                        reversed.contains(receipt.getStockOperationId())))
                .toList();
        return view(request, views, replayed);
    }

    private RestockDtos.RequestView view(
            RestockRequest request, List<RestockDtos.ReceiptView> receiptViews, boolean replayed) {
        var orgId = request.getOrganizationId();
        return view(
                request,
                receiptViews,
                replayed,
                items.summaries(orgId, List.of(request.getItemId())).get(request.getItemId()),
                locations.summaries(orgId, List.of(request.getLocationId())).get(request.getLocationId()));
    }

    private static RestockDtos.RequestView view(
            RestockRequest request,
            List<RestockDtos.ReceiptView> receiptViews,
            boolean replayed,
            ItemQueries.ItemSummary item,
            LocationQueries.Summary location) {
        return new RestockDtos.RequestView(
                request.getId(),
                request.getNumber(),
                label(request),
                request.getStatus(),
                request.getItemId(),
                item == null ? null : item.name(),
                item == null ? null : item.sku(),
                item == null ? null : item.unit().name(),
                item != null && item.archived(),
                request.getLocationId(),
                location == null ? null : location.name(),
                location == null ? null : location.parentName(),
                Quantities.text(request.getRequestedQuantity()),
                request.getApprovedQuantity() == null ? null : Quantities.text(request.getApprovedQuantity()),
                Quantities.text(request.getReceivedQuantity()),
                Quantities.text(request.remaining()),
                request.getRequesterId(),
                request.getRequesterName(),
                request.getNote(),
                request.getCreatedAt(),
                request.getDecidedByName(),
                request.getDecidedAt(),
                request.getDecisionNote(),
                request.getClosedByName(),
                request.getClosedAt(),
                request.getCloseReason(),
                request.getUpdatedAt(),
                request.getVersion(),
                receiptViews,
                replayed);
    }

    private void record(UUID orgId, String action, RestockRequest request, String... pairs) {
        var actor = access.actor();
        var item = items.summaries(orgId, List.of(request.getItemId())).get(request.getItemId());
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("name", item == null ? "" : item.name());
        metadata.put("request", label(request));
        if (item != null) metadata.put("unit", item.unit().name());
        for (int index = 0; index + 1 < pairs.length; index += 2)
            if (pairs[index + 1] != null) metadata.put(pairs[index], pairs[index + 1]);
        audit.record(orgId, actor.userId(), actor.displayName(), action, "REQUEST", request.getId(), metadata);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
