// WITH_STDLIB
// WITH_COROUTINES

import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

open class EmptyContinuation(override val context: CoroutineContext = EmptyCoroutineContext) : Continuation<Any?> {
    companion object : EmptyContinuation()
    override fun resumeWith(result: Result<Any?>) { result.getOrThrow() }
}

var counter = 0

suspend fun suspendWithValue(value: Int): Int = suspendCoroutineUninterceptedOrReturn { x ->
    counter++
    x.resume(value)
    COROUTINE_SUSPENDED
}

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(EmptyContinuation)
}

fun box(): String {
    counter = 0
    var sum = 0
    
    builder {
        val a = suspendWithValue(10)
        val b = suspendWithValue(20)
        val c = suspendWithValue(30)
        sum = a + b + c
    }
    
    if (sum != 60) return "Failed: expected 60, got $sum"
    if (counter != 3) return "Failed: expected 3 suspensions, got $counter"
    return "OK"
}
