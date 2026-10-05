package com.openlibrary.inventory;

import com.openlibrary.catalog.CatalogDtos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Authorisation for these paths lives in {@code SecurityConfig}. */
@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryService inventory;
    private final LabelRenderer labels;

    public InventoryController(InventoryService inventory, LabelRenderer labels) {
        this.inventory = inventory;
        this.labels = labels;
    }

    @GetMapping("/copies")
    public CatalogDtos.PageResponse<InventoryDtos.CopySummary> copies(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) CopyStatus status,
            @RequestParam(required = false) Long bookId,
            @RequestParam(required = false) Long locationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {

        return inventory.searchCopies(q, status, bookId, locationId, page, size, sort);
    }

    @GetMapping("/copies/{id}")
    public InventoryDtos.CopySummary copy(@PathVariable Long id) {
        return inventory.copy(id);
    }

    @PostMapping("/copies/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryDtos.BulkResult createCopies(@Valid @RequestBody InventoryDtos.CreateCopiesRequest body) {
        return inventory.createCopies(body);
    }

    @PostMapping("/copies/{id}/move")
    public InventoryDtos.CopySummary move(@PathVariable Long id,
                                          @Valid @RequestBody InventoryDtos.MoveRequest body) {
        return inventory.move(id, body.toLocationId());
    }

    @PatchMapping("/copies/{id}/status")
    public InventoryDtos.CopySummary changeStatus(@PathVariable Long id,
                                                  @Valid @RequestBody InventoryDtos.ChangeStatusRequest body) {
        return inventory.changeStatus(id, body.status());
    }

    @GetMapping("/copies/{id}/moves")
    public List<InventoryDtos.MoveView> moves(@PathVariable Long id) {
        return inventory.movesOf(id);
    }

    /** Printable label: the scanned barcode on top, the QR below. */
    @GetMapping(value = "/copies/{id}/label.png", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] label(@PathVariable Long id) {
        return labels.render(inventory.copy(id));
    }

    /** Scans: takes whatever the camera or the USB scanner produced. */
    @GetMapping("/lookup")
    public InventoryDtos.CopySummary lookup(@RequestParam String code) {
        return inventory.findByScannedCode(code)
                .map(found -> inventory.copy(found.getId()))
                .orElseThrow(() -> new com.openlibrary.shared.ApiException(
                        HttpStatus.NOT_FOUND, "copy_not_found",
                        "Ningun ejemplar con el codigo " + code + "."));
    }

    @GetMapping("/locations")
    public List<InventoryDtos.LocationSummary> locations() {
        return inventory.locationTree();
    }

    @PostMapping("/locations")
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryDtos.LocationSummary createLocation(@Valid @RequestBody InventoryDtos.LocationRequest body) {
        return inventory.createLocation(body);
    }

    @PutMapping("/locations/{id}")
    public InventoryDtos.LocationSummary updateLocation(@PathVariable Long id,
                                                        @Valid @RequestBody InventoryDtos.LocationRequest body) {
        return inventory.updateLocation(id, body);
    }

    @DeleteMapping("/locations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLocation(@PathVariable Long id) {
        inventory.deleteLocation(id);
    }

    /** Catalogue plus stock, for the inventory screen. */
    @GetMapping("/books")
    public List<InventoryDtos.BookStock> books() {
        return inventory.stock();
    }
}