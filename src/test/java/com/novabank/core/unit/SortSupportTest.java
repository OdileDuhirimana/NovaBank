package com.novabank.core.unit;

import com.novabank.core.common.SortSupport;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Isolated unit test for {@link SortSupport} — the shared sort-parsing helper used by both
 * {@code TransactionQueryService} and {@code AdminService}. Specifically covers the security
 * property that matters most here: an arbitrary field name outside the caller's allow-list must
 * always be rejected, never silently passed through into a SQL ORDER BY.
 */
class SortSupportTest {

    private static final Set<String> ALLOWED = Set.of("amount", "occurredAt");

    @Test
    void returnsNullForABlankSortParameter() {
        assertThat(SortSupport.buildSort(null, ALLOWED)).isNull();
        assertThat(SortSupport.buildSort("  ", ALLOWED)).isNull();
    }

    @Test
    void defaultsToAscendingWhenNoDirectionIsGiven() {
        Sort sort = SortSupport.buildSort("amount", ALLOWED);

        assertThat(sort.getOrderFor("amount")).isNotNull();
        assertThat(sort.getOrderFor("amount").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void parsesAnExplicitDescendingDirection() {
        Sort sort = SortSupport.buildSort("occurredAt,desc", ALLOWED);

        assertThat(sort.getOrderFor("occurredAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void rejectsAFieldOutsideTheAllowList() {
        assertThatThrownBy(() -> SortSupport.buildSort("password", ALLOWED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid sort field");
    }
}
