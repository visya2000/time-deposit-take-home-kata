package org.ikigaidigital.application;

import org.ikigaidigital.application.port.out.TransactionRunner;

import java.util.function.Supplier;

/** Fake of the transaction port: restores the repository's snapshot when the work throws. */
class InMemoryTransactionRunner implements TransactionRunner {

    private final InMemoryTimeDepositRepository repository;

    InMemoryTransactionRunner(InMemoryTimeDepositRepository repository) {
        this.repository = repository;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        repository.beginTransaction();
        try {
            T result = work.get();
            repository.commit();
            return result;
        } catch (RuntimeException | Error e) {
            repository.rollback();
            throw e;
        }
    }
}
