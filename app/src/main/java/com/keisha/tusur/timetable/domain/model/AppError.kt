package com.keisha.tusur.timetable.domain.model

/**
 * Ошибки слоя данных. UI переводит их в понятные сообщения,
 * не показывая технические подробности пользователю.
 */
sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Нет сети, таймаут, обрыв соединения. */
    class Network(cause: Throwable? = null) : AppError("network", cause)

    /** Сервер ответил 5xx или неожиданным кодом. */
    class Server(val code: Int, cause: Throwable? = null) : AppError("server:$code", cause)

    /** 404: группа или факультет исчезли с сайта. */
    class NotFound(cause: Throwable? = null) : AppError("not_found", cause)

    /** Сайт ответил, но разметка или формат не совпали с ожиданиями. */
    class Parse(cause: Throwable? = null) : AppError("parse", cause)

    class Unknown(cause: Throwable? = null) : AppError("unknown", cause)
}
