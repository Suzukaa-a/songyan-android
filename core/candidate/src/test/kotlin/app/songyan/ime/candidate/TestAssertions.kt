package app.songyan.ime.candidate

import org.junit.Assert

/**
 * The module's tests read like the iOS suites they are translated from (kotlin.test argument order),
 * while the build only ships JUnit 4. These thin wrappers keep expected/actual and the message in a
 * consistent position across the suite.
 */

fun assertTrue(actual: Boolean) = Assert.assertTrue(actual)

fun assertTrue(
    actual: Boolean,
    message: String,
) = Assert.assertTrue(message, actual)

fun assertFalse(actual: Boolean) = Assert.assertFalse(actual)

fun assertFalse(
    actual: Boolean,
    message: String,
) = Assert.assertFalse(message, actual)

fun assertEquals(
    expected: Any?,
    actual: Any?,
) = Assert.assertEquals(expected, actual)

fun assertEquals(
    expected: Any?,
    actual: Any?,
    message: String,
) = Assert.assertEquals(message, expected, actual)

fun assertEquals(
    expected: Double,
    actual: Double,
    delta: Double,
) = Assert.assertEquals(expected, actual, delta)

fun assertEquals(
    expected: Double,
    actual: Double,
    delta: Double,
    message: String,
) = Assert.assertEquals(message, expected, actual, delta)

fun assertNotEquals(
    unexpected: Any?,
    actual: Any?,
) = Assert.assertNotEquals(unexpected, actual)

fun assertNotEquals(
    unexpected: Any?,
    actual: Any?,
    message: String,
) = Assert.assertNotEquals(message, unexpected, actual)

fun assertNull(actual: Any?) = Assert.assertNull(actual)

fun assertNull(
    actual: Any?,
    message: String,
) = Assert.assertNull(message, actual)

fun assertNotNull(actual: Any?) = Assert.assertNotNull(actual)

/** Runs [block] and returns the thrown exception, failing when nothing is thrown. */
inline fun <reified T : Throwable> assertFailsWith(
    message: String? = null,
    block: () -> Unit,
): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) return error
        throw AssertionError(message ?: ("expected " + T::class.simpleName + " but threw " + error), error)
    }
    throw AssertionError(message ?: ("expected " + T::class.simpleName + " but nothing was thrown"))
}
