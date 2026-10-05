package com.openlibrary.inventory;

/** Rooms hold corridors, corridors hold shelves; a depot stands alone. */
public enum LocationKind {
    SALA,
    PASILLO,
    ESTANTE,
    DEPOSITO;

    /** Only a shelf (or a depot) can actually hold a physical copy. */
    public boolean holdsCopies() {
        return this == ESTANTE || this == DEPOSITO;
    }

    /** A parent this kind may hang under, or null when it stands alone. */
    public LocationKind parentKind() {
        return switch (this) {
            case SALA -> null;
            case PASILLO -> SALA;
            case ESTANTE -> PASILLO;
            case DEPOSITO -> null;
        };
    }
}