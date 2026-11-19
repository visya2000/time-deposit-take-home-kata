package org.ikigaidigital.adapter.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Read-only. No back-reference to the deposit: a (default EAGER) @ManyToOne could trigger extra
 * statements and break the single-statement GET.
 */
@Entity
@Table(name = "withdrawals")
class WithdrawalEntity {

    @Id
    private Integer id;

    private BigDecimal amount;

    private LocalDate date;

    protected WithdrawalEntity() {
        // for JPA
    }

    Integer getId() {
        return id;
    }

    BigDecimal getAmount() {
        return amount;
    }

    LocalDate getDate() {
        return date;
    }
}
