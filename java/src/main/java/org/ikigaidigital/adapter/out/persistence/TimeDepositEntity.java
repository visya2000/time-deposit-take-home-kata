package org.ikigaidigital.adapter.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Column names follow Spring Boot's default naming strategy (planType -> plan_type). */
@Entity
@Table(name = "time_deposits")
class TimeDepositEntity {

    // No @GeneratedValue: the application never inserts deposits.
    @Id
    private Integer id;

    private String planType;

    private int days;

    private BigDecimal balance;

    // Internal scheduling marker of the balance update (PLAN.md §3); not exposed by the API.
    private LocalDate lastInterestAppliedOn;

    // Unidirectional, loaded only by the GET fetch join.
    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "time_deposit_id")
    private List<WithdrawalEntity> withdrawals = new ArrayList<>();

    protected TimeDepositEntity() {
        // for JPA
    }

    Integer getId() {
        return id;
    }

    String getPlanType() {
        return planType;
    }

    int getDays() {
        return days;
    }

    BigDecimal getBalance() {
        return balance;
    }

    List<WithdrawalEntity> getWithdrawals() {
        return withdrawals;
    }

    /** The only state change the application makes: flushed by dirty checking on commit. */
    void applyInterest(BigDecimal newBalance, LocalDate appliedOn) {
        this.balance = newBalance;
        this.lastInterestAppliedOn = appliedOn;
    }
}
