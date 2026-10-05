package com.openlibrary.inventory;

/** Where a copy is in its life. Loans move it between DISPONIBLE and PRESTADO. */
public enum CopyStatus {
    DISPONIBLE,
    PRESTADO,
    MANTENIMIENTO,
    PERDIDO;

    /** Only a copy that is on the shelf can be lent. */
    public boolean isLendable() {
        return this == DISPONIBLE;
    }

    /** Only staff may set this by hand; loans set PRESTADO on their own. */
    public boolean isManualOnly() {
        return this == MANTENIMIENTO || this == PERDIDO;
    }
}