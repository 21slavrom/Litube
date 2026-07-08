package com.hhst.youtubelite.core

/**
 * Discriminated outcome of an operation that can fail. Replaces the silent
 * "return null on error" convention so callers can distinguish "no result"
 * from "operation failed".
 *
 * Kotlin idioms:
 *   val v: String = result.getOrThrow()
 *   val v: String? = result.getOrNull()
 *   result.map { it.length }
 *
 * Java idioms:
 *   if (result instanceof Result.Success) { ... ((Result.Success<?>) result).getValue() ... }
 *   if (result instanceof Result.Failure) { ... ((Result.Failure) result).getError() ... }
 */
sealed class Result<out T> {

    data class Success<T>(val value: T) : Result<T>()

    data class Failure(val error: Throwable) : Result<Nothing>()

    companion object {

        @JvmStatic
        fun <T> success(value: T): Result<T> = Success(value)

        @JvmStatic
        fun failure(error: Throwable): Result<Nothing> = Failure(error)

        @JvmStatic
        fun <T> of(block: () -> T): Result<T> = try {
            Success(block())
        } catch (e: Throwable) {
            Failure(e)
        }
    }
}

fun <T> Result<T>.getOrNull(): T? = when (this) {
    is Result.Success -> value
    is Result.Failure -> null
}

fun <T> Result<T>.getOrThrow(): T = when (this) {
    is Result.Success -> value
    is Result.Failure -> throw error
}

fun <T> Result<T>.isSuccess(): Boolean = this is Result.Success

fun <T> Result<T>.isFailure(): Boolean = this is Result.Failure

fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.Success(transform(value))
    is Result.Failure -> this
}

inline fun <T, R> Result<T>.flatMap(transform: (T) -> Result<R>): Result<R> = when (this) {
    is Result.Success -> transform(value)
    is Result.Failure -> this
}

inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T> {
    if (this is Result.Success) action(value)
    return this
}

inline fun <T> Result<T>.onFailure(action: (Throwable) -> Unit): Result<T> {
    if (this is Result.Failure) action(error)
    return this
}

inline fun <T> resultOf(block: () -> T): Result<T> = try {
    Result.Success(block())
} catch (e: Throwable) {
    Result.Failure(e)
}
