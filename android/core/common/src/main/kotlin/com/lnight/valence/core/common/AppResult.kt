package com.lnight.valence.core.common

sealed interface AppResult<out T> {
    data class Success<T>(
        val value: T,
    ) : AppResult<T>

    data class Failure(
        val error: AppError,
    ) : AppResult<Nothing>
}

sealed interface AppError {
    data class Validation(
        val reason: ValidationFailure,
    ) : AppError

    data class Persistence(
        val reason: PersistenceFailure,
    ) : AppError
}

enum class ValidationFailure {
    BlankMessageText,
}

enum class PersistenceFailure {
    WriteFailed,
}
