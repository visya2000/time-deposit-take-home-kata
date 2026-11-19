package org.ikigaidigital.application.port.out;

import java.util.function.Supplier;

/** Runs work in one transaction: committed when it returns, rolled back when it throws. */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);
}
