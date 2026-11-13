package com.backroom.exports;

import static com.backroom.access.Role.MANAGER;
import static com.backroom.access.Role.OWNER;
import static com.backroom.access.Role.values;

import com.backroom.access.AccessPolicy;
import com.backroom.audit.AuditService;
import com.backroom.shared.error.InvalidRequestException;
import com.backroom.shared.error.NotFoundException;
import com.backroom.shared.error.PayloadTooLargeException;
import java.io.Writer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spreadsheet exports for Owners and Managers. This is the one read-only reporting module that reads several modules'
 * tables directly with SQL, so that a 50,000-row export is a single streamed query instead of thousands of lookups.
 * Every table in every query is filtered by organization_id. It never writes anything except its own audit event.
 */
@Service
public class ExportService {
    static final int MAX_ROWS = 50_000;
    static final int DEFAULT_DAYS = 90;

    public enum Kind {
        ITEMS("items", false),
        STOCK("stock", false),
        MOVEMENTS("movements", true),
        REQUESTS("requests", true);
        final String file;
        final boolean dated;

        Kind(String file, boolean dated) {
            this.file = file;
            this.dated = dated;
        }

        static Kind of(String file) {
            return Arrays.stream(values())
                    .filter(kind -> kind.file.equals(file))
                    .findFirst()
                    .orElseThrow(NotFoundException::new);
        }
    }
    /** What one export will contain; checked and audited before any bytes are sent. */
    public record Plan(Kind kind, UUID orgId, LocalDate from, LocalDate to, long rows) {
        String filename() {
            return "backroom-" + kind.file
                    + (kind.dated ? "-" + from + "-to-" + to : "-" + LocalDate.now(ZoneOffset.UTC)) + ".csv";
        }

        Instant start() {
            return from.atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        Instant end() {
            return to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
    }

    private static final Map<String, String> UNITS =
            Map.of("EACH", "each", "GRAM", "g", "KILOGRAM", "kg", "MILLILITER", "mL", "LITER", "L");
    private static final Map<String, String> OPERATIONS = Map.of(
            "OPENING",
            "Starting quantity",
            "RECEIPT",
            "Received",
            "USAGE",
            "Used",
            "WASTE",
            "Wasted",
            "SALE",
            "Sold",
            "TRANSFER",
            "Transfer",
            "COUNT",
            "Count",
            "REVERSAL",
            "Reversal");
    private static final Map<String, String> STATUSES = Map.of(
            "SUBMITTED",
            "Submitted",
            "APPROVED",
            "Approved",
            "PARTIALLY_RECEIVED",
            "Partly received",
            "FULFILLED",
            "Received",
            "REJECTED",
            "Rejected",
            "CANCELLED",
            "Cancelled",
            "CLOSED_SHORT",
            "Closed short");

    private static final String ITEMS_FROM = " from items i where i.organization_id = ?";
    private static final String STOCK_FROM = """
             from stock_balances b
             join items i on i.organization_id = b.organization_id and i.id = b.item_id
             join locations l on l.organization_id = b.organization_id and l.id = b.location_id
             left join locations p on p.organization_id = l.organization_id and p.id = l.parent_id
             left join item_placements pl on pl.organization_id = b.organization_id and pl.item_id = b.item_id and pl.location_id = b.location_id and pl.active
             where b.organization_id = ? and (b.quantity <> 0 or pl.id is not null)
            """;
    private static final String MOVEMENTS_FROM = """
             from stock_movements m
             join stock_operations o on o.organization_id = m.organization_id and o.id = m.operation_id
             join items i on i.organization_id = m.organization_id and i.id = m.item_id
             join locations l on l.organization_id = m.organization_id and l.id = m.location_id
             left join locations p on p.organization_id = l.organization_id and p.id = l.parent_id
             where m.organization_id = ? and m.created_at >= ? and m.created_at < ?
            """;
    private static final String REQUESTS_FROM = """
             from restock_requests r
             join items i on i.organization_id = r.organization_id and i.id = r.item_id
             join locations l on l.organization_id = r.organization_id and l.id = r.location_id
             left join locations p on p.organization_id = l.organization_id and p.id = l.parent_id
             where r.organization_id = ? and r.created_at >= ? and r.created_at < ?
            """;

    private final AccessPolicy access;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    ExportService(AccessPolicy access, AuditService audit, DataSource dataSource) {
        this.access = access;
        this.audit = audit;
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setFetchSize(500);
    }

    /** Authorizes, validates the range, counts, refuses oversized exports and records the audit event. */
    @Transactional
    public Plan prepare(UUID orgId, String file, LocalDate from, LocalDate to) {
        access.lock(orgId, OWNER, MANAGER);
        var kind = Kind.of(file);
        var end = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
        var start = from == null ? end.minusDays(DEFAULT_DAYS) : from;
        if (start.isAfter(end)) throw new InvalidRequestException("The start date must be on or before the end date.");
        var plan = new Plan(kind, orgId, start, end, 0);
        var rows = count(plan);
        if (rows > MAX_ROWS) {
            throw new PayloadTooLargeException(
                    "EXPORT_TOO_LARGE",
                    "That export has " + rows + " rows; the limit is " + MAX_ROWS + ". Choose a shorter date range.");
        }
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("export", kind.file);
        metadata.put("rows", String.valueOf(rows));
        if (kind.dated) {
            metadata.put("from", start.toString());
            metadata.put("to", end.toString());
        }
        var actor = access.actor();
        audit.record(orgId, actor.userId(), actor.displayName(), "DATA_EXPORTED", "ORGANIZATION", orgId, metadata);
        return new Plan(kind, orgId, start, end, rows);
    }

    /** Streams the rows. Read-only and in one transaction, so the file is a consistent snapshot. */
    @Transactional(readOnly = true)
    public void write(Plan plan, Writer out) {
        var csv = new CsvWriter(out);
        csv.bom();
        switch (plan.kind()) {
            case ITEMS -> {
                csv.row(List.of("Item", "SKU", "Unit", "On hand", "Status", "Item ID"));
                jdbc.query(
                        """
                        select i.id, i.name, i.sku, i.unit, i.archived,
                               coalesce((select sum(b.quantity) from stock_balances b where b.organization_id = i.organization_id and b.item_id = i.id), 0) as on_hand
                        """ + ITEMS_FROM + " order by lower(i.name), i.id",
                        rs -> {
                            csv.row(Arrays.asList(
                                    rs.getString("name"),
                                    rs.getString("sku"),
                                    unit(rs),
                                    rs.getBigDecimal("on_hand"),
                                    rs.getBoolean("archived") ? "Archived" : "Active",
                                    rs.getString("id")));
                        },
                        plan.orgId());
            }
            case STOCK -> {
                csv.row(List.of(
                        "Item", "SKU", "Location", "Unit", "On hand", "Minimum", "Low", "Item ID", "Location ID"));
                jdbc.query(
                        "select i.id as item_id, i.name, i.sku, i.unit, l.id as location_id, l.name as location, p.name as parent, b.quantity, pl.minimum_quantity"
                                + STOCK_FROM
                                + " order by lower(i.name), i.id, lower(coalesce(p.name, '')), lower(l.name), l.id",
                        rs -> {
                            var minimum = rs.getBigDecimal("minimum_quantity");
                            var quantity = rs.getBigDecimal("quantity");
                            csv.row(Arrays.asList(
                                    rs.getString("name"),
                                    rs.getString("sku"),
                                    path(rs),
                                    unit(rs),
                                    quantity,
                                    minimum,
                                    minimum == null ? "" : quantity.compareTo(minimum) < 0 ? "Yes" : "No",
                                    rs.getString("item_id"),
                                    rs.getString("location_id")));
                        },
                        plan.orgId());
            }
            case MOVEMENTS -> {
                csv.row(List.of(
                        "Date (UTC)",
                        "Operation",
                        "Item",
                        "SKU",
                        "Location",
                        "Unit",
                        "Change",
                        "Balance after",
                        "Person",
                        "Reference",
                        "Note",
                        "Reversed",
                        "Operation ID"));
                jdbc.query(
                        """
                        select m.created_at, o.type, o.id as operation_id, o.actor_name, o.reference, o.note, i.name, i.sku, i.unit,
                               l.name as location, p.name as parent, m.quantity_change, m.balance_after,
                               exists (select 1 from stock_operations r where r.organization_id = o.organization_id and r.reverses_operation_id = o.id) as reversed
                        """ + MOVEMENTS_FROM + " order by m.created_at, m.id",
                        rs -> {
                            csv.row(Arrays.asList(
                                    instant(rs, "created_at"),
                                    OPERATIONS.getOrDefault(rs.getString("type"), rs.getString("type")),
                                    rs.getString("name"),
                                    rs.getString("sku"),
                                    path(rs),
                                    unit(rs),
                                    rs.getBigDecimal("quantity_change"),
                                    rs.getBigDecimal("balance_after"),
                                    rs.getString("actor_name"),
                                    rs.getString("reference"),
                                    rs.getString("note"),
                                    rs.getBoolean("reversed") ? "Yes" : "No",
                                    rs.getString("operation_id")));
                        },
                        plan.orgId(),
                        java.sql.Timestamp.from(plan.start()),
                        java.sql.Timestamp.from(plan.end()));
            }
            case REQUESTS -> {
                csv.row(List.of(
                        "Request",
                        "Status",
                        "Item",
                        "SKU",
                        "Location",
                        "Unit",
                        "Requested",
                        "Approved",
                        "Received",
                        "Requested by",
                        "Requested at (UTC)",
                        "Note",
                        "Decided by",
                        "Decided at (UTC)",
                        "Decision note",
                        "Closed by",
                        "Closed at (UTC)",
                        "Close reason"));
                jdbc.query(
                        """
                        select r.number, r.status, r.requested_quantity, r.approved_quantity, r.received_quantity, r.requester_name, r.created_at, r.note,
                               r.decided_by_name, r.decided_at, r.decision_note, r.closed_by_name, r.closed_at, r.close_reason,
                               i.name, i.sku, i.unit, l.name as location, p.name as parent
                        """ + REQUESTS_FROM + " order by r.number",
                        rs -> {
                            csv.row(Arrays.asList(
                                    "R-" + rs.getInt("number"),
                                    STATUSES.getOrDefault(rs.getString("status"), rs.getString("status")),
                                    rs.getString("name"),
                                    rs.getString("sku"),
                                    path(rs),
                                    unit(rs),
                                    rs.getBigDecimal("requested_quantity"),
                                    rs.getBigDecimal("approved_quantity"),
                                    rs.getBigDecimal("received_quantity"),
                                    rs.getString("requester_name"),
                                    instant(rs, "created_at"),
                                    rs.getString("note"),
                                    rs.getString("decided_by_name"),
                                    instant(rs, "decided_at"),
                                    rs.getString("decision_note"),
                                    rs.getString("closed_by_name"),
                                    instant(rs, "closed_at"),
                                    rs.getString("close_reason")));
                        },
                        plan.orgId(),
                        java.sql.Timestamp.from(plan.start()),
                        java.sql.Timestamp.from(plan.end()));
            }
        }
    }

    private long count(Plan plan) {
        var orgId = plan.orgId();
        var start = java.sql.Timestamp.from(plan.start());
        var end = java.sql.Timestamp.from(plan.end());
        Long rows = switch (plan.kind()) {
            case ITEMS -> jdbc.queryForObject("select count(*)" + ITEMS_FROM, Long.class, orgId);
            case STOCK -> jdbc.queryForObject("select count(*)" + STOCK_FROM, Long.class, orgId);
            case MOVEMENTS -> jdbc.queryForObject("select count(*)" + MOVEMENTS_FROM, Long.class, orgId, start, end);
            case REQUESTS -> jdbc.queryForObject("select count(*)" + REQUESTS_FROM, Long.class, orgId, start, end);
        };
        return rows == null ? 0 : rows;
    }

    private static String unit(ResultSet rs) throws SQLException {
        return UNITS.getOrDefault(rs.getString("unit"), rs.getString("unit"));
    }

    private static String path(ResultSet rs) throws SQLException {
        var parent = rs.getString("parent");
        return parent == null ? rs.getString("location") : parent + " › " + rs.getString("location");
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
