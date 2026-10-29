package com.unowhy.companion.net

import kotlinx.coroutines.CancellationException

/** Comme runCatching, mais l'annulation de la coroutine passe : elle n'est jamais rangée dans un Result. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
