package org.ikigaidigital.adapter.out.persistence;

import org.ikigaidigital.application.port.out.TransactionRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Component
class SpringTransactionRunner implements TransactionRunner {

    private final TransactionTemplate transactionTemplate;

    SpringTransactionRunner(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        // RuntimeExceptions and Errors roll back and are rethrown unchanged by TransactionTemplate.
        return transactionTemplate.execute(status -> work.get());
    }
}
