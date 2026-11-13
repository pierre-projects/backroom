package com.backroom.restocking;

import static com.backroom.access.Role.EMPLOYEE;
import static com.backroom.access.Role.MANAGER;
import static com.backroom.access.Role.OWNER;
import static com.backroom.restocking.RequestStatus.APPROVED;
import static com.backroom.restocking.RequestStatus.CANCELLED;
import static com.backroom.restocking.RequestStatus.CLOSED_SHORT;
import static com.backroom.restocking.RequestStatus.OPEN;
import static com.backroom.restocking.RequestStatus.RECEIVING;
import static com.backroom.restocking.RequestStatus.REJECTED;
import static com.backroom.restocking.RequestStatus.SUBMITTED;

import com.backroom.access.AccessPolicy;
import com.backroom.access.Role;
import com.backroom.audit.AuditService;
import com.backroom.inventory.ItemQueries;
import com.backroom.inventory.Quantities;
import com.backroom.inventory.StockDtos;
import com.backroom.inventory.StockService;
import com.backroom.locations.LocationQueries;
import com.backroom.shared.web.ApiException;
import com.backroom.shared.web.PageResponse;
import java.time.Instant;
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
        if (item.archived()) throw ApiException.conflict("ARCHIVED", "Archived items can't be requested.");
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
        if (request.status != SUBMITTED) throw invalid("Only submitted requests can be approved.");
        var item = items.require(orgId, request.itemId);
        var approved = in.approvedQuantity() == null
                ? request.requestedQuantity
                : Quantities.positive(item.unit(), in.approvedQuantity());
        var actor = access.actor();
        request.approvedQuantity = approved;
        request.status = APPROVED;
        request.decidedBy = actor.userId();
        request.decidedByName = actor.displayName();
        request.decidedAt = Instant.now();
        request.decisionNote = blankToNull(in.note());
        touch(request);
        record(
                orgId,
                "RESTOCK_APPROVED",
                request,
                "quantity",
                Quantities.text(approved),
                "requested",
                Quantities.text(request.requestedQuantity));
        return detail(orgId, request, false);
    }

    public RestockDtos.RequestView reject(UUID orgId, UUID id, RestockDtos.Reason in) {
        access.require(orgId, OWNER, MANAGER);
        var request = lockForDecision(orgId, id, in.expectedVersion());
        if (request.status != SUBMITTED) throw invalid("Only submitted requests can be rejected.");
        var actor = access.actor();
        request.status = REJECTED;
        request.decidedBy = actor.userId();
        request.decidedByName = actor.displayName();
        request.decidedAt = Instant.now();
        request.decisionNote = in.reason().strip();
        touch(request);
        record(orgId, "RESTOCK_REJECTED", request, "reason", request.decisionNote);
        return detail(orgId, request, false);
    }

    public RestockDtos.RequestView cancel(UUID orgId, UUID id, RestockDtos.Version in) {
        var role = access.require(orgId);
        var request = lockForDecision(orgId, id, in.expectedVersion());
        var actor = access.actor();
        if (role == EMPLOYEE && !request.requesterId.equals(actor.userId())) throw ApiException.forbidden();
        if (request.status != SUBMITTED) throw invalid("Only requests that haven't been reviewed can be cancelled.");
        request.status = CANCELLED;
        request.closedBy = actor.userId();
        request.closedByName = actor.displayName();
        request.closedAt = Instant.now();
        touch(request);
        record(orgId, "RESTOCK_CANCELLED", request);
        return detail(orgId, request, false);
    }

    public RestockDtos.RequestView closeShort(UUID orgId, UUID id, RestockDtos.Reason in) {
        access.require(orgId, OWNER, MANAGER);
        var request = lockForDecision(orgId, id, in.expectedVersion());
        if (!RECEIVING.contains(request.status))
            throw invalid("Only approved requests that are still waiting for stock can be closed short.");
        var actor = access.actor();
        request.status = CLOSED_SHORT;
        request.closedBy = actor.userId();
        request.closedByName = actor.displayName();
        request.closedAt = Instant.now();
        request.closeReason = in.reason().strip();
        touch(request);
        record(
                orgId,
                "RESTOCK_CLOSED_SHORT",
                request,
                "received",
                Quantities.text(request.receivedQuantity),
                "reason",
                request.closeReason);
        return detail(orgId, request, false);
    }

    /**
     * Records a delivery. The operation key makes retries safe: it is checked after the request lock is held,
     * so a duplicate that waited behind the original sees the original's receipt and replays it.
     */
    public RestockDtos.RequestView receive(UUID orgId, UUID id, RestockDtos.Receive in) {
        access.require(orgId);
        var request = requests.lockById(orgId, id).orElseThrow(ApiException::notFound);
        var item = items.require(orgId, request.itemId);
        var quantity = Quantities.positive(item.unit(), in.quantity());
        var actor = access.actor();

        var earlier = stock.operationIdForKey(orgId, in.operationKey());
        if (earlier.isPresent()) {
            var receipt = receipts.findByOrganizationIdAndStockOperationId(orgId, earlier.get());
            if (receipt.isPresent()
                    && receipt.get().requestId.equals(id)
                    && receipt.get().receiverId.equals(actor.userId())
                    && receipt.get().quantity.compareTo(quantity) == 0) {
                log.info(
                        "Restock delivery replayed: type=RESTOCK_RECEIPT operationKey={} organizationId={}",
                        in.operationKey(),
                        orgId);
                return detail(orgId, request, true);
            }
            throw ApiException.conflict(
                    "IDEMPOTENCY_KEY_REUSED", "This operation key was already used for a different request.");
        }
        if (request.status == SUBMITTED)
            throw ApiException.conflict("NOT_APPROVED", "This request hasn't been approved yet.");
        if (!RECEIVING.contains(request.status))
            throw invalid("This request is closed. Record extra stock as an ordinary receipt.");
        if (quantity.compareTo(request.remaining()) > 0) {
            throw ApiException.conflict(
                    "OVER_RECEIPT",
                    "Only " + Quantities.text(request.remaining())
                            + " is still expected on this request. Record extra stock as an ordinary receipt.");
        }

        var operation = stock.receive(
                orgId,
                new StockDtos.Receipt(
                        in.operationKey(),
                        request.itemId,
                        request.locationId,
                        quantity,
                        label(request),
                        blankToNull(in.note())));
        receipts.save(new RestockReceipt(
                orgId, request.getId(), operation.id(), quantity, actor.userId(), actor.displayName()));
        request.receivedQuantity = request.receivedQuantity.add(quantity);
        request.settle();
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
                Quantities.text(request.receivedQuantity));
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
                orgId, result.stream().map(request -> request.itemId).toList());
        var locationSummaries = locations.summaries(
                orgId, result.stream().map(request -> request.locationId).toList());
        return PageResponse.from(result.map(request -> view(
                request,
                List.of(),
                false,
                itemSummaries.get(request.itemId),
                locationSummaries.get(request.locationId))));
    }

    @Transactional(readOnly = true)
    public RestockDtos.RequestView get(UUID orgId, UUID id) {
        access.require(orgId);
        return detail(orgId, requests.findByOrganizationIdAndId(orgId, id).orElseThrow(ApiException::notFound), false);
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
        var request = requests.lockById(orgId, id).orElseThrow(ApiException::notFound);
        request.checkVersion(expectedVersion);
        return request;
    }

    private static ApiException invalid(String message) {
        return ApiException.conflict("INVALID_TRANSITION", message);
    }

    private void touch(RestockRequest request) {
        request.updatedAt = Instant.now();
        requests.flush();
    }

    static String label(RestockRequest request) {
        return "R-" + request.number;
    }

    private RestockDtos.RequestView detail(UUID orgId, RestockRequest request, boolean replayed) {
        var list = receipts.findByOrganizationIdAndRequestIdOrderByCreatedAtAscIdAsc(orgId, request.getId());
        var reversed = stock.reversedAmong(
                orgId, list.stream().map(receipt -> receipt.stockOperationId).toList());
        var views = list.stream()
                .map(receipt -> new RestockDtos.ReceiptView(
                        receipt.getId(),
                        Quantities.text(receipt.quantity),
                        receipt.receiverName,
                        receipt.getCreatedAt(),
                        receipt.stockOperationId,
                        reversed.contains(receipt.stockOperationId)))
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
                items.summaries(orgId, List.of(request.itemId)).get(request.itemId),
                locations.summaries(orgId, List.of(request.locationId)).get(request.locationId));
    }

    private static RestockDtos.RequestView view(
            RestockRequest request,
            List<RestockDtos.ReceiptView> receiptViews,
            boolean replayed,
            ItemQueries.ItemSummary item,
            LocationQueries.Summary location) {
        return new RestockDtos.RequestView(
                request.getId(),
                request.number,
                label(request),
                request.status,
                request.itemId,
                item == null ? null : item.name(),
                item == null ? null : item.sku(),
                item == null ? null : item.unit().name(),
                item != null && item.archived(),
                request.locationId,
                location == null ? null : location.name(),
                location == null ? null : location.parentName(),
                Quantities.text(request.requestedQuantity),
                request.approvedQuantity == null ? null : Quantities.text(request.approvedQuantity),
                Quantities.text(request.receivedQuantity),
                Quantities.text(request.remaining()),
                request.requesterId,
                request.requesterName,
                request.note,
                request.getCreatedAt(),
                request.decidedByName,
                request.decidedAt,
                request.decisionNote,
                request.closedByName,
                request.closedAt,
                request.closeReason,
                request.updatedAt,
                request.getVersion(),
                receiptViews,
                replayed);
    }

    private void record(UUID orgId, String action, RestockRequest request, String... pairs) {
        var actor = access.actor();
        var item = items.summaries(orgId, List.of(request.itemId)).get(request.itemId);
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
