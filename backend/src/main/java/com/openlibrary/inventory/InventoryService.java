package com.openlibrary.inventory;

import com.openlibrary.catalog.BookRepository;
import com.openlibrary.catalog.CatalogDtos;
import com.openlibrary.shared.ApiException;
import com.openlibrary.shared.AuditService;
import com.openlibrary.shared.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class InventoryService {

    private static final Set<String> SORTABLE = Set.of("code", "status", "createdat", "book");

    private final CopyRepository copies;
    private final CopyMoveRepository moves;
    private final LocationRepository locations;
    private final BookRepository books;
    private final AuditService audit;
    private final CurrentUser caller;
    private final JdbcTemplate jdbc;

    public InventoryService(CopyRepository copies, CopyMoveRepository moves,
                            LocationRepository locations, BookRepository books,
                            AuditService audit, CurrentUser caller, JdbcTemplate jdbc) {
        this.copies = copies;
        this.moves = moves;
        this.locations = locations;
        this.books = books;
        this.audit = audit;
        this.caller = caller;
        this.jdbc = jdbc;
    }

    // ── Locations ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<InventoryDtos.LocationSummary> locationTree() {
        var all = locations.findAll(Sort.by(Sort.Direction.ASC, "sortOrder", "code"));

        Map<Long, List<Location>> children = new LinkedHashMap<>();
        List<Location> roots = new ArrayList<>();
        for (Location location : all) {
            if (location.getParentId() == null) {
                roots.add(location);
            } else {
                children.computeIfAbsent(location.getParentId(), key -> new ArrayList<>()).add(location);
            }
        }
        return roots.stream().map(root -> toTree(root, children)).toList();
    }

    private InventoryDtos.LocationSummary toTree(Location location,
                                                 Map<Long, List<Location>> children) {
        List<InventoryDtos.LocationSummary> kids = children.getOrDefault(location.getId(), List.of())
                .stream().map(child -> toTree(child, children)).toList();
        // A room holds nothing itself; its copies sit on the shelves below, so the
        // count has to roll up or "Sala principal: 0" reads as empty next to a full shelf.
        long total = kids.stream().mapToLong(InventoryDtos.LocationSummary::copies).sum()
                + copies.countByLocationId(location.getId());
        return new InventoryDtos.LocationSummary(
                location.getId(), location.getCode(), location.getName(), location.getKind(),
                location.getParentId(), kids, total);
    }

    @Transactional
    public InventoryDtos.LocationSummary createLocation(InventoryDtos.LocationRequest body) {
        if (locations.existsByCode(body.code().trim())) {
            throw conflict("location_code_taken", "Ya existe una ubicacion con ese codigo.", "code");
        }
        requireValidParent(body.kind(), body.parentId());

        var location = new Location(body.code().trim(), body.name().trim(), body.kind(), body.parentId());
        location.placeAt(body.x(), body.y(), body.z(), body.width(), body.depth(), body.height());
        Location saved = locations.save(location);
        audit.record(caller.id(), "location.created", "location", saved.getId(),
                Map.of("code", saved.getCode()));

        return toTree(saved, Map.of());
    }

    @Transactional
    public InventoryDtos.LocationSummary updateLocation(Long id, InventoryDtos.LocationRequest body) {
        Location location = findLocation(id);
        if (body.parentId() != null && body.parentId().equals(id)) {
            throw badRequest("location_cycle", "Una ubicacion no puede ser su propia unidad.",
                    List.of(new ApiException.FieldError("parentId", "Elige otra ubicacion")));
        }
        requireValidParent(body.kind(), body.parentId());

        // Walking up from the new parent must not reach this location.
        for (Long cursor = body.parentId(); cursor != null; ) {
            if (cursor.equals(id)) {
                throw badRequest("location_cycle",
                        "Esa ubicacion ya esta dentro de esta otra.",
                        List.of(new ApiException.FieldError("parentId", "Ciclo en el arbol")));
            }
            var parent = locations.findById(cursor);
            cursor = parent.map(Location::getParentId).orElse(null);
        }

        location.rename(body.name().trim());
        audit.record(caller.id(), "location.updated", "location", id, Map.of());
        return toTree(location, Map.of());
    }

    @Transactional
    public void deleteLocation(Long id) {
        Location location = findLocation(id);
        if (locations.countByParentId(id) > 0) {
            throw conflict("location_has_children",
                    "Quita antes las ubicaciones que dependen de esta.", null);
        }
        if (copies.countByLocationId(id) > 0) {
            throw conflict("location_has_copies",
                    "Esta ubicacion todavia guarda ejemplares.", null);
        }
        locations.delete(location);
        audit.record(caller.id(), "location.deleted", "location", id,
                Map.of("code", location.getCode()));
    }

    /**
     * The parent is optional: a library registers its shelves before anyone has
     * measured the room, so a shelf may sit at the root and be parented later.
     * When a parent is given it has to be the kind that can hold it.
     */
    private void requireValidParent(LocationKind kind, Long parentId) {
        LocationKind expected = kind.parentKind();
        if (parentId == null) {
            return;
        }
        if (expected == null) {
            throw badRequest("invalid_parent", "Esa unidad no cuelga de ninguna otra.", null);
        }
        Location parent = findLocation(parentId);
        if (parent.getKind() != expected) {
            throw badRequest("invalid_parent",
                    "Un " + kind.name().toLowerCase() + " cuelga de una "
                            + expected.name().toLowerCase() + ", no de una "
                            + parent.getKind().name().toLowerCase() + ".", null);
        }
    }

    // ── Copies ──────────────────────────────────────────────────────────────

    /**
     * Adds N copies of a book at once, because nobody stocks a shelf one label at
     * a time. The sequence lives in Postgres so two librarians never collide.
     */
    @Transactional
    public InventoryDtos.BulkResult createCopies(InventoryDtos.CreateCopiesRequest body) {
        if (!books.existsById(body.bookId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "book_not_found",
                    "No existe el libro " + body.bookId() + ".");
        }
        if (body.locationId() != null) {
            requireHoldable(findLocation(body.locationId()));
        }

        var created = new ArrayList<Copy>();
        for (int i = 0; i < body.quantity(); i++) {
            long sequence = nextSequence();
            Copy copy = new Copy(body.bookId(), CopyCode.code(sequence),
                    CopyCode.barcode(sequence), CopyCode.qrPayload(sequence));
            copy.setDetails(body.locationId(), body.acquiredAt(), body.price(), null);
            created.add(copies.save(copy));

            if (body.locationId() != null) {
                moves.save(new CopyMove(copy.getId(), null, body.locationId(), caller.id()));
            }
        }

        audit.record(caller.id(), "copies.created", "book", body.bookId(),
                Map.of("quantity", body.quantity()));

        return new InventoryDtos.BulkResult(body.quantity(), created.stream().map(this::toSummary).toList());
    }

    @Transactional(readOnly = true)
    public CatalogDtos.PageResponse<InventoryDtos.CopySummary> searchCopies(
            String q, CopyStatus status, Long bookId, Long locationId, int page, int size, String sort) {

        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200),
                sortOf(sort));
        String term = q == null || q.isBlank() ? null : q.trim();

        Page<Copy> found = copies.search(term,
                "%" + com.openlibrary.catalog.Book.fold(term) + "%",
                status, bookId, locationId, pageable);

        Map<Long, String> titles = titlesFor(found.getContent());
        return CatalogDtos.PageResponse.of(found.map(copy -> toSummary(copy, titles)));
    }

    @Transactional(readOnly = true)
    public InventoryDtos.CopySummary copy(Long id) {
        Copy copy = findCopy(id);
        return toSummary(copy, titlesFor(List.of(copy)));
    }

    @Transactional(readOnly = true)
    public List<InventoryDtos.MoveView> movesOf(Long id) {
        findCopy(id);
        var codes = new LinkedHashMap<Long, String>();
        locations.findAll().forEach(location -> codes.put(location.getId(), location.getCode()));

        return moves.findByCopyIdOrderByMovedAtDesc(id).stream()
                .map(move -> new InventoryDtos.MoveView(
                        move.getId(),
                        move.getFromLocationId() == null ? null : codes.get(move.getFromLocationId()),
                        move.getToLocationId() == null ? null : codes.get(move.getToLocationId()),
                        move.getMovedAt().toString(),
                        caller.email()))
                .toList();
    }

    @Transactional
    public InventoryDtos.CopySummary move(Long id, Long toLocationId) {
        Copy copy = findCopy(id);
        Location target = findLocation(toLocationId);
        requireHoldable(target);

        Long from = copy.getLocationId();
        copy.moveTo(toLocationId);
        copies.save(copy);
        moves.save(new CopyMove(id, from, toLocationId, caller.id()));

        audit.record(caller.id(), "copy.moved", "copy", id, Map.of("to", target.getCode()));
        return toSummary(copy, titlesFor(List.of(copy)));
    }

    @Transactional
    public InventoryDtos.CopySummary changeStatus(Long id, CopyStatus status) {
        if (!status.isManualOnly() && status != CopyStatus.DISPONIBLE) {
            throw badRequest("status_not_manual", "Ese estado lo cambia el prestamo.", null);
        }
        Copy copy = findCopy(id);
        copy.setStatus(status);
        copies.save(copy);

        audit.record(caller.id(), "copy.status_changed", "copy", id,
                Map.of("status", status.name()));
        return toSummary(copy, titlesFor(List.of(copy)));
    }

    /** Catalogue rows with their stock, for the inventory screen. */
    @Transactional(readOnly = true)
    public List<InventoryDtos.BookStock> stock() {
        var summaries = books.findAll(Sort.by(Sort.Direction.ASC, "title")).stream()
                .map(book -> new BookRow(
                        book.getId(), book.getTitle(), book.getSubtitle(),
                        book.getSearchText(),
                        book.getAuthors().stream().map(link -> link.getAuthor().getName()).toList(),
                        book.getIsbn13()))
                .toList();

        var counts = new LinkedHashMap<Long, int[]>();
        copies.findAll().forEach(copy -> {
            int[] byStatus = counts.computeIfAbsent(copy.getBookId(),
                    key -> new int[CopyStatus.values().length]);
            byStatus[copy.getStatus().ordinal()]++;
        });

        var result = new ArrayList<InventoryDtos.BookStock>(summaries.size());
        for (BookRow row : summaries) {
            int[] byStatus = counts.getOrDefault(row.id(), new int[CopyStatus.values().length]);
            int total = 0;
            for (int count : byStatus) {
                total += count;
            }
            result.add(new InventoryDtos.BookStock(row.id(), row.title(), row.subtitle(),
                    row.authors(), row.isbn(), total,
                    byStatus[CopyStatus.DISPONIBLE.ordinal()]));
        }
        return result;
    }

    // ── internals ───────────────────────────────────────────────────────────

    private record BookRow(Long id, String title, String subtitle, String searchText,
                           List<String> authors, String isbn) {
    }

    private long nextSequence() {
        Number value = jdbc.queryForObject("select nextval('copy_code_sequence')", Number.class);
        return value.longValue();
    }

    private void requireHoldable(Location location) {
        if (!location.getKind().holdsCopies()) {
            throw badRequest("location_not_holdable",
                    "Un ejemplar se guarda en un estante o un deposito, no en "
                            + location.getKind().name().toLowerCase() + ".", null);
        }
    }

    private Map<Long, String> titlesFor(List<Copy> page) {
        var ids = page.stream().map(Copy::getBookId).distinct().toList();
        var titles = new LinkedHashMap<Long, String>();
        if (ids.isEmpty()) {
            return titles;
        }
        books.findAllById(ids).forEach(book -> titles.put(book.getId(), book.getTitle()));
        return titles;
    }

    private InventoryDtos.CopySummary toSummary(Copy copy) {
        return toSummary(copy, titlesFor(List.of(copy)));
    }

    private InventoryDtos.CopySummary toSummary(Copy copy, Map<Long, String> titles) {
        return new InventoryDtos.CopySummary(
                copy.getId(), copy.getCode(), copy.getBarcode(), copy.getQr(), copy.getStatus(),
                copy.getBookId(), titles.get(copy.getBookId()), null,
                copy.getLocationId(),
                copy.getLocationId() == null ? null
                        : locations.findById(copy.getLocationId()).map(Location::getCode).orElse(null));
    }

    private static Sort sortOf(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.ASC, "code");
        }
        for (String token : sort.split(",")) {
            var bits = token.split(":");
            String field = bits[0].trim().toLowerCase(Locale.ROOT);
            if (!SORTABLE.contains(field)) {
                continue;
            }
            boolean descending = bits.length > 1 && "desc".equalsIgnoreCase(bits[1].trim());
            String property = field.equals("createdat") ? "createdAt" : field;
            Sort.Order order = descending
                    ? Sort.Order.desc(property)
                    : Sort.Order.asc(property);
            return Sort.by(property.equals("code") ? order.ignoreCase() : order);
        }
        return Sort.by(Sort.Order.asc("code").ignoreCase());
    }

    private Copy findCopy(Long id) {
        return copies.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "copy_not_found",
                        "No existe el ejemplar " + id + "."));
    }

    private Location findLocation(Long id) {
        return locations.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "location_not_found",
                        "No existe la ubicacion " + id + "."));
    }

    private static ApiException badRequest(String code, String detail, List<ApiException.FieldError> fields) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, detail, fields);
    }

    private static ApiException conflict(String code, String detail, String field) {
        return new ApiException(HttpStatus.CONFLICT, code, detail,
                field == null ? List.of()
                        : List.of(new ApiException.FieldError(field, detail)));
    }

    /** Resolves a scanned code: the barcode if it looks like one, the human code otherwise. */
    public Optional<Copy> findByScannedCode(String scanned) {
        if (scanned == null || scanned.isBlank()) {
            return Optional.empty();
        }
        String value = scanned.trim();
        return CopyCode.hasValidEan13Checksum(value)
                ? copies.findByBarcode(value)
                : copies.findByCode(value);
    }
}