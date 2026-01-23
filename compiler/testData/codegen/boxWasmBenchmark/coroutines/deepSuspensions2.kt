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

var resumeContA: (() -> Unit)? = null
var resumeContB: (() -> Unit)? = null

var resumeCoroutine: (() -> Unit)? = null

fun builder(c: suspend () -> Unit): ManualContinuation {
    val cont = ManualContinuation()
    c.startCoroutine(cont)
    return cont
}

suspend fun deepA(n: Int): String {
    return suspendCoroutine { x ->
        resumeCoroutineA = {
            resumeCoroutineB!!.invoke()
        }
        COROUTINE_SUSPENDED
    }
}

suspend fun deepB(n: Int): String {
    return suspendCoroutine { x ->
        val tmp = resumeCoroutineA!!
        resumeCoroutineB = {
            tmp()
        }
        COROUTINE_SUSPENDED
    }
}

fun box(): String {
    val depth = 20
    val resultA: String? = null
    val resultB: String? = null
    val contA = builder {
        suspendCoroutine { cont ->
            resumeCoroutineA = {
                cont.resume()
            }
            COROUTINE_SUSPENDED
        }
        resultA = deepA(depth / 2)
    }
    val contB = builder {
        suspendCoroutine { cont ->
            resumeCoroutineB = {
                cont.resume()
            }
            COROUTINE_SUSPENDED
        }
        resultB = deepB(depth / 2)
    }
    for (i in 1..depth) {
        resumeCoroutine!!.invoke()
    }
    return result
}
