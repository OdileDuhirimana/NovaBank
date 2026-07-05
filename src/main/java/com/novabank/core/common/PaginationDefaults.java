package com.novabank.core.common;

/**
 * Default page size for every paginated listing endpoint (transactions, admin accounts, audit
 * logs, fraud logs) when the caller does not specify one.
 *
 * Centralized here — rather than duplicated as a literal in {@code TransactionQueryService} and
 * every admin listing endpoint, or left attached to a single service class that other,
 * unrelated controllers then had to import from (as it previously was, on {@code
 * TransactionService}, coupling {@code AdminController} to a class it otherwise has no
 * business depending on) — so the default page size has one owner independent of which
 * feature happens to need it. Exposed as a String too since
 * {@code @RequestParam(defaultValue = )} requires a compile-time constant string.
 */
public final class PaginationDefaults {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final String DEFAULT_PAGE_SIZE_STR = "20";

    private PaginationDefaults() {
    }
}
