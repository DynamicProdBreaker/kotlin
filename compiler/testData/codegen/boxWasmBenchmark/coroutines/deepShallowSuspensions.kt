// WITH_STDLIB
// WITH_COROUTINES

import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

class ManualContinuation : Continuation<Unit> {
    override val context: CoroutineContext = EmptyCoroutineContext
    var completed = false
    override fun resumeWith(result: Result<Unit>) {
        result.getOrThrow()
        completed = true
    }
}

fun builder(c: suspend () -> Unit): ManualContinuation {
    val cont = ManualContinuation()
    c.startCoroutine(cont)
    return cont
}

suspend fun deep(n: Int) {
    if (n == 0) return
    val cont = builder {
        deep(n - 1)
    }
    if (!cont.completed) {
        throw RuntimeException("Should have completed synchronously")
    }
}

fun box(): String {
    val cont = builder {
        deep(20)
    }
    if (!cont.completed) return "Failed: not completed"
    return "OK"
}
