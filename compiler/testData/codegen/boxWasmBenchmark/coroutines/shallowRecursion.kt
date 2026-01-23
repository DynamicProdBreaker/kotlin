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

suspend fun shallow(n: Int) {
    for (i in 0 until n) {
        val cont = builder {
            // Do some work to make it a bit heavier
            var sum = 0
            for (j in 0 until 10) {
                sum += j
            }
        }
        if (!cont.completed) {
            throw RuntimeException("Should have completed synchronously")
        }
    }
}

fun box(): String {
    val cont = builder {
        shallow(100)
    }
    if (!cont.completed) return "Failed: not completed"
    return "OK"
}
