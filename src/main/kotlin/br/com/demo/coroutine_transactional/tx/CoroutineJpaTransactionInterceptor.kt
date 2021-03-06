package br.com.demo.coroutine_transactional.tx

import org.springframework.core.KotlinDetector
import org.springframework.lang.Nullable
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.TransactionManager
import org.springframework.transaction.interceptor.DefaultTransactionAttribute
import org.springframework.transaction.interceptor.TransactionAttribute
import org.springframework.transaction.interceptor.TransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.util.ClassUtils
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext

/*
    NON PRODUCTION READY CODE, CODING JUST FOR FUN!!!!
 */
class CoroutineJpaTransactionInterceptor(
    transactionAttributeSource: TransactionAttributeSource,
    manager: TransactionManager
) : TransactionInterceptor(manager, transactionAttributeSource) {

    private class TransactionContinuation<T>(
        private val continuation: Continuation<T>,
        private val callback: (Result<T>) -> Unit
    ) : Continuation<T> {
        override val context: CoroutineContext = continuation.context

        override fun resumeWith(result: Result<T>) {
            callback.runCatching { invoke(result) }
            this.continuation.resumeWith(result)
        }
    }

    override fun invokeWithinTransaction(method: Method, targetClass: Class<*>?, invocation: InvocationCallback): Any? {

        if (!KotlinDetector.isSuspendingFunction(method)) {
            return super.invokeWithinTransaction(method, targetClass, invocation)
        }

        val txAttr = transactionAttributeSource?.getTransactionAttribute(method, targetClass)
        val ptm = determineTransactionManager(txAttr) as JpaTransactionManager
        val joinpointIdentification = methodIdentification(method, targetClass, txAttr)
        val txInfo = createTransactionIfNecessary(ptm, txAttr, joinpointIdentification)

        return (invocation as CoroutinesInvocationCallback).installTransactionContinuation(txInfo).run {
            proceedWithInvocation()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun CoroutinesInvocationCallback.installTransactionContinuation(
        txInfo: TransactionInfo
    ): CoroutinesInvocationCallback {
        val transactionContinuation =
            TransactionContinuation(continuation as Continuation<Any?>) { result ->
                result.onFailure { throwable ->
                    cleanupTransactionInfo(txInfo)
                    completeTransactionAfterThrowing(txInfo, throwable)
                }.onSuccess {
                    cleanupTransactionInfo(txInfo)
                    commitTransactionAfterReturning(txInfo)
                }
            }

        arguments.apply { this[size - 1] = transactionContinuation }

        return this
    }

    private fun methodIdentification(
        method: Method,
        @Nullable targetClass: Class<*>?,
        @Nullable txAttr: TransactionAttribute?
    ): String {
        val identification =
            methodIdentification(method, targetClass) ?: (txAttr as? DefaultTransactionAttribute)?.descriptor
        return identification ?: ClassUtils.getQualifiedMethodName(method, targetClass)
    }
}
