package com.openlibrary.loans;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.openlibrary.catalog.CatalogDtos;

/** Authorisation for these paths lives in {@code SecurityConfig}. */
@RestController
@RequestMapping("/loans")
public class LoanController {

    private final LoanService loans;

    public LoanController(LoanService loans) {
        this.loans = loans;
    }

    /**
     * Staff see every loan; a reader passing their own id only ever sees theirs,
     * because the service filters on whatever id arrives and the role decides which
     * id the UI is allowed to send.
     */
    @GetMapping
    public CatalogDtos.PageResponse<LoanDtos.LoanSummary> list(
            @RequestParam(defaultValue = "OPEN") LoanState state,
            @RequestParam(required = false) Long readerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return loans.search(state, readerId, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LoanDtos.LoanSummary borrow(@Valid @RequestBody LoanDtos.BorrowRequest body) {
        return loans.borrow(body.copyId(), body.readerId());
    }

    @PostMapping("/{id}/renew")
    public LoanDtos.LoanSummary renew(@PathVariable Long id) {
        return loans.renew(id);
    }

    @PostMapping("/{id}/return")
    public LoanDtos.LoanSummary giveBack(@PathVariable Long id) {
        return loans.giveBack(id);
    }

    @GetMapping("/settings")
    public LoanDtos.SettingsSummary settings() {
        return loans.settingsSummary();
    }

    /**
     * The reader's own corner. It has to be declared before the staff-wide
     * {@code GET /loans/**} rule, otherwise a card holder gets a 403 for asking
     * about their own books.
     */
    @GetMapping("/mine")
    public LoanDtos.MyLibrary mine() {
        return loans.myLibrary();
    }

    /** Only ever the caller's own queue; the service reads the id from the session. */
    @GetMapping("/reservations")
    public List<LoanDtos.ReservationSummary> myReservations() {
        return loans.myReservations();
    }

    /** The whole waiting list, for the desk. Staff-only by URL. */
    @GetMapping("/queue")
    public List<LoanDtos.ReservationSummary> queue() {
        return loans.allReservations();
    }

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public LoanDtos.ReservationSummary reserve(@Valid @RequestBody LoanDtos.ReserveRequest body) {
        return loans.reserve(body.bookId());
    }

    @DeleteMapping("/reservations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelReservation(@PathVariable Long id) {
        loans.cancelReservation(id);
    }
}
