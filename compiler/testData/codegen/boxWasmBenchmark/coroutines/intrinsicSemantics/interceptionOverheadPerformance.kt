// WITH_STDLIB
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

// Performance test for continuation interception overhead
// Measures the cost of dispatching through an interceptor

var dispatchCount = 0

suspend fun suspendWithInterception(): String = suspendCoroutineUninterceptedOrReturn { continuation ->
    // Resume through intercepted continuation to measure dispatch overhead
    continuation.intercepted().resume("OK")
    COROUTINE_SUSPENDED
}

class DispatchingInterceptor : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        return DispatchedContinuation(continuation)
    }
}

class DispatchedContinuation<T>(
    private val continuation: Continuation<T>
) : Continuation<T> {
    override val context: CoroutineContext = continuation.context
    
    override fun resumeWith(result: Result<T>) {
        // Count each dispatch
        dispatchCount++
        continuation.resumeWith(result)
    }
}

fun box(): String {
    dispatchCount = 0
    var result = "FAIL"
    
    val coroutine: suspend () -> String = {
        var accumulated = ""
        repeat(10) {
            val value = suspendWithInterception()
            accumulated = value
        }
        accumulated
    }
    
    coroutine.startCoroutine(object : Continuation<String> {
        override val context: CoroutineContext
            get() = DispatchingInterceptor()
        
        override fun resumeWith(value: Result<String>) {
            result = value.getOrNull() ?: "FAIL"
        }
    })
    
    // Expect 10 suspensions + 1 final completion = 11 dispatches
    return if (dispatchCount == 11 && result == "OK") "OK" else "FAIL: $dispatchCount"
}
