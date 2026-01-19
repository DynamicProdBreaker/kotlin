class MyIterable : Iterable<String> {
    override fun iterator() = listOf("a", "b").iterator()
}

fun test() {
    val custom = MyIterable()
    for (it<caret>em in custom) {
        println(item)
    }
}
