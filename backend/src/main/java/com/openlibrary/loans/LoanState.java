package com.openlibrary.loans;

/** Which loans a list is asking for. The desk wants {@link #OPEN} and nothing else. */
public enum LoanState {
    /** Still out with a reader: the default, because that is what needs action. */
    OPEN,
    /** Already given back: history. */
    CLOSED,
    /** Both. */
    ALL
}
