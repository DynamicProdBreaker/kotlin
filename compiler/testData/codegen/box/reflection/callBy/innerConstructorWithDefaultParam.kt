// TARGET_BACKEND: JVM
// WITH_REFLECT

class Outer(val s1: String) {
    inner class Inner(val s2: String, val s3: String = "") {
        fun concat() = s1 + s2 + s3
    }
}

fun box(): String {
    val unboundCtor = Outer::Inner

    val params = unboundCtor.parameters

    val c = unboundCtor.callBy(
        mapOf(
            params[0] to Outer("O"),
            params[1] to "K"
        )
    ).concat()

    return c
}