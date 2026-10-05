package com.openlibrary.loans;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.openlibrary.catalog.BookRepository;
import com.openlibrary.catalog.CatalogDtos;
import com.openlibrary.inventory.Copy;
import com.openlibrary.inventory.CopyRepository;
import com.openlibrary.inventory.CopyStatus;
import com.openlibrary.shared.ApiException;
import com.openlibrary.shared.AuditService;
import com.openlibrary.shared.CurrentUser;

/**
 * Loads the facts and lets {@link LoanPolicy} decide. Nothing here decides a rule
 * on its own, so the borrowing rules have exactly one implementation.
 */
@Service
public class LoanService {

    private static final String KEY_DAYS = "loans.days_default";
    private static final String KEY_LIMIT = "loans.max_active_per_reader";
    private static final String KEY_RENEWALS = "loans.max_renewals";

    private final LoanRepository loans;
    private final ReservationRepository reservations;
    private final CopyRepository copies;
    private final BookRepository books;
    private final AuditService audit;
    private final CurrentUser caller;
    private final JdbcTemplate jdbc;
    // Not a constructor argument: nothing in production ever supplies a different
    // one, and the policy tests pass their own clock straight to LoanPolicy.
    private final Clock clock = Clock.systemUTC();

    public LoanService(LoanRepository loans, ReservationRepository reservations,
                       CopyRepository copies, BookRepository books, AuditService audit,
                       CurrentUser caller, JdbcTemplate jdbc) {
        this.loans = loans;
        this.reservations = reservations;
        this.copies = copies;
        this.books = books;
        this.audit = audit;
        this.caller = caller;
        this.jdbc = jdbc;
    }

    // ── borrowing ────────────────────────────────────────────────────────────

    @Transactional
    public LoanDtos.LoanSummary borrow(Long copyId, Long readerId) {
        var copy = copies.findById(copyId)
                .orElseThrow(() -> notFound("copy_not_found", "No existe el ejemplar " + copyId + "."));
        // Checked before the insert: a mistyped reader number at the desk has to
        // read as "no such reader", not as a foreign key blowing up into a 500.
        requireReader(readerId);

        var active = loans.findFirstByCopyIdAndReturnedAtIsNull(copyId);
        var snapshot = active.isEmpty()
                ? LoanPolicy.CopySnapshot.free(copyId)
                : LoanPolicy.CopySnapshot.lent(copyId, active.get().getId());

        var request = new LoanPolicy.Request(copy.getBookId(), readerId,
                Math.toIntExact(loans.countByUserIdAndReturnedAtIsNull(readerId)),
                Math.toIntExact(loans.countOverdueFor(readerId, startOfToday())),
                queueOf(copy.getBookId()), snapshot);

        LoanPolicy.Loan decided;
        try {
            decided = LoanPolicy.checkBorrow(request, settings(), clock);
        } catch (LoanPolicy.RuleViolation e) {
            throw new ApiException(statusFor(e.code()), e.code(), e.getMessage());
        }

        var loan = loans.save(new Loan(copyId, readerId, decided.borrowedAt(), decided.dueAt()));
        copy.setStatus(CopyStatus.PRESTADO);
        copies.save(copy);
        // Lending to a reader who was waiting closes their place in the queue.
        reservations.findByBookIdAndUserIdAndFulfilledAtIsNullAndCancelledAtIsNull(
                        copy.getBookId(), readerId)
                .ifPresent(reservation -> reservation.fulfil(clock.instant()));
        reservations.saveAll(reservations.findByUser(readerId));

        audit.record(readerId, "loans.borrowed", "copy", copyId,
                Map.of("dueAt", decided.dueAt().toString()));
        return summary(loan);
    }

    @Transactional
    public LoanDtos.LoanSummary renew(Long loanId) {
        var loan = loans.findById(loanId)
                .orElseThrow(() -> notFound("loan_not_found", "No existe el prestamo " + loanId + "."));

        var policyLoan = new LoanPolicy.Loan(loan.getCopyId(), loan.getBorrowedAt(),
                loan.getDueAt(), loan.getRenewals(), loan.getReturnedAt(),
                LoanPolicy.Status.ACTIVE);
        var bookId = copies.findById(loan.getCopyId()).map(Copy::getBookId).orElseThrow();

        LoanPolicy.Loan decided;
        try {
            decided = LoanPolicy.checkRenewal(policyLoan, settings(), clock, queueOf(bookId));
        } catch (LoanPolicy.RuleViolation e) {
            throw new ApiException(statusFor(e.code()), e.code(), e.getMessage());
        }

        loan.renew(decided.dueAt());
        audit.record(caller.id(), "loans.renewed", "loan", loanId,
                Map.of("dueAt", decided.dueAt().toString()));
        return summary(loan);
    }

    @Transactional
    public LoanDtos.LoanSummary giveBack(Long loanId) {
        var loan = loans.findById(loanId)
                .orElseThrow(() -> notFound("loan_not_found", "No existe el prestamo " + loanId + "."));
        if (!loan.isOpen()) {
            throw new ApiException(HttpStatus.CONFLICT, "loan_not_active",
                    "Ese prestamo ya estaba devuelto.");
        }
        loan.returned(clock.instant());
        copies.findById(loan.getCopyId()).ifPresent(copy -> {
            copy.setStatus(CopyStatus.DISPONIBLE);
            copies.save(copy);
        });
        audit.record(caller.id(), "loans.returned", "loan", loanId, Map.of());
        return summary(loan);
    }

    /**
     * The desk list shows what is still out; closed loans are history and are a
     * separate view. Mixing them made the screen claim a returned book was "en plazo".
     */
    @Transactional(readOnly = true)
    public CatalogDtos.PageResponse<LoanDtos.LoanSummary> search(
            LoanState state, Long readerId, int page, int size) {
        var pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 100),
                Sort.by(Sort.Direction.DESC, "borrowedAt"));
        var found = switch (state) {
            case OPEN -> loans.findOpen(readerId, pageable);
            case CLOSED -> loans.findClosed(readerId, pageable);
            case ALL -> readerId == null ? loans.findAll(pageable) : loans.search(readerId, pageable);
        };
        return new CatalogDtos.PageResponse<>(
                found.getContent().stream().map(this::summary).toList(),
                found.getNumber(), found.getSize(), found.getTotalElements(), found.getTotalPages());
    }

    @Transactional(readOnly = true)
    public LoanDtos.SettingsSummary settingsSummary() {
        var settings = settings();
        return new LoanDtos.SettingsSummary(settings.loanDays().toDays() > 0
                ? Math.toIntExact(settings.loanDays().toDays()) : 0,
                settings.readerLimit(), settings.maxRenewals());
    }

    // ── reservations ─────────────────────────────────────────────────────────

    @Transactional
    public LoanDtos.ReservationSummary reserve(Long bookId) {
        var readerId = caller.id();
        if (books.findById(bookId).isEmpty()) {
            throw notFound("book_not_found", "No existe el libro " + bookId + ".");
        }
        boolean holdsIt = jdbc.queryForObject("""
                select count(*) from copies c
                join loans l on l.copy_id = c.id and l.returned_at is null
                where c.book_id = ? and l.user_id = ?
                """, Long.class, bookId, readerId) > 0;
        if (holdsIt) {
            throw new ApiException(HttpStatus.CONFLICT, "already_has_the_book",
                    "Ya tienes este libro prestado.");
        }
        var existing = reservations
                .findByBookIdAndUserIdAndFulfilledAtIsNullAndCancelledAtIsNull(bookId, readerId);
        if (existing.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "already_reserved",
                    "Ya estas en la cola de este libro.");
        }

        var saved = reservations.save(new Reservation(bookId, readerId, clock.instant()));
        audit.record(readerId, "reservations.created", "book", bookId, Map.of());
        return reservationSummary(saved);
    }

    @Transactional
    public void cancelReservation(Long id) {
        var reservation = reservations.findById(id)
                .orElseThrow(() -> notFound("reservation_not_found", "No existe la reserva " + id + "."));
        if (!reservation.isOpen()) {
            throw new ApiException(HttpStatus.CONFLICT, "reservation_not_open",
                    "Esa reserva ya no esta en la cola.");
        }
        reservation.cancel(clock.instant());
        audit.record(caller.id(), "reservations.cancelled", "book", reservation.getBookId(), Map.of());
    }

    @Transactional(readOnly = true)
    public List<LoanDtos.ReservationSummary> myReservations() {
        return reservations.findByUser(caller.id()).stream()
                .map(this::reservationSummary).toList();
    }

    @Transactional(readOnly = true)
    public List<LoanDtos.ReservationSummary> allReservations() {
        return jdbc.query("""
                select r.id, r.book_id, b.title, r.created_at, r.fulfilled_at, r.cancelled_at
                from reservations r join books b on b.id = r.book_id
                order by r.created_at
                """, (rs, n) -> new LoanDtos.ReservationSummary(
                        rs.getLong("id"), rs.getLong("book_id"), rs.getString("title"), null,
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("fulfilled_at") == null && rs.getTimestamp("cancelled_at") == null));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * The borrowing rules as configuration, so a library can change them without a
     * redeploy. A missing or unparseable value falls back to the documented default
     * rather than failing a checkout.
     */
    LoanPolicy.Settings settings() {
        var defaults = LoanPolicy.Settings.defaults();
        return new LoanPolicy.Settings(
                Duration.ofDays(number(KEY_DAYS, defaults.loanDays().toDays())),
                (int) number(KEY_LIMIT, defaults.readerLimit()),
                (int) number(KEY_RENEWALS, defaults.maxRenewals()),
                defaults.loanPeriodDays());
    }

    private long number(String key, long fallback) {
        try {
            var rows = jdbc.query("select value from app_config where key = ?",
                    (rs, n) -> rs.getString("value"), key);
            return rows.isEmpty() ? fallback : Long.parseLong(rows.get(0).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private List<LoanPolicy.QueueEntry> queueOf(Long bookId) {
        return reservations.queueFor(bookId).stream()
                .map(r -> new LoanPolicy.QueueEntry(r.getId(), r.getUserId(), r.getCreatedAt()))
                .toList();
    }

    private Instant startOfToday() {
        return LoanPolicy.dueDate(clock.instant()).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
    }

    private LoanDtos.LoanSummary summary(Loan loan) {
        var row = jdbc.query("""
                select c.code, c.barcode, c.book_id, b.title, u.email
                from copies c
                join books b on b.id = c.book_id
                join users u on u.id = ?
                where c.id = ?
                """, (rs, n) -> new Object[] {
                        rs.getString("code"), rs.getString("barcode"), rs.getLong("book_id"),
                        rs.getString("title"), rs.getString("email")},
                loan.getUserId(), loan.getCopyId());
        var facts = row.isEmpty() ? null : row.get(0);
        var now = clock.instant();
        return new LoanDtos.LoanSummary(
                loan.getId(), loan.getCopyId(),
                facts == null ? null : (String) facts[0],
                facts == null ? null : (String) facts[1],
                facts == null ? null : (Long) facts[2],
                facts == null ? null : (String) facts[3],
                loan.getUserId(),
                facts == null ? null : (String) facts[4],
                loan.getBorrowedAt(), loan.getDueAt(), loan.getReturnedAt(), loan.getRenewals(),
                loan.isOpen() && LoanPolicy.isOverdue(loan.getDueAt(), now));
    }

    private LoanDtos.ReservationSummary reservationSummary(Reservation reservation) {
        var title = jdbc.query("select title from books where id = ?",
                (rs, n) -> rs.getString("title"), reservation.getBookId());
        return new LoanDtos.ReservationSummary(reservation.getId(), reservation.getBookId(),
                title.isEmpty() ? null : title.get(0), null, reservation.getCreatedAt(),
                reservation.isOpen());
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    /** A reader id that does not exist is a typo at the desk, not a server fault. */
    private void requireReader(Long readerId) {
        Integer exists = jdbc.queryForObject(
                "select count(*) from users where id = ? and active", Integer.class, readerId);
        if (exists == null || exists == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "reader_not_found",
                    "No existe ningun lector con el id " + readerId + ".");
        }
    }

    /** "Not available" and "somebody was waiting" are conflicts, not bad input. */
    private static HttpStatus statusFor(String code) {
        return switch (code) {
            case "reader_limit_reached", "book_reserved_by_other_reader",
                 "reader_has_overdue_loans" -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}
