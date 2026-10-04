package com.lumina.reader.core.download

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSURLErrorAppTransportSecurityRequiresSecureConnection
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorClientCertificateRejected
import platform.Foundation.NSURLErrorClientCertificateRequired
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorDataNotAllowed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorInternationalRoamingOff
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorSecureConnectionFailed
import platform.Foundation.NSURLErrorServerCertificateHasBadDate
import platform.Foundation.NSURLErrorServerCertificateHasUnknownRoot
import platform.Foundation.NSURLErrorServerCertificateNotYetValid
import platform.Foundation.NSURLErrorServerCertificateUntrusted
import platform.Foundation.NSURLErrorTimedOut

/**
 * NSURLSession failures arrive as [DarwinHttpRequestException] wrapping the
 * NSError; its NSURLErrorDomain code is mapped to the texts Android shows for
 * the corresponding java.net exceptions. A blocked plain-http request (App
 * Transport Security) counts as a secure-connection error, which is what the
 * user can fix (or the Info.plist exception is missing).
 */
actual fun platformNetworkErrorMessage(error: Throwable): String? {
    val origin = (error as? DarwinHttpRequestException)?.origin ?: return null
    if (origin.domain != NSURLErrorDomain) return null
    return when (origin.code) {
        NSURLErrorNotConnectedToInternet,
        NSURLErrorCannotFindHost,
        NSURLErrorDNSLookupFailed,
        NSURLErrorDataNotAllowed,
        NSURLErrorInternationalRoamingOff -> "Нет подключения к интернету или сервер не найден"

        NSURLErrorTimedOut -> "Сервер слишком долго не отвечает"

        NSURLErrorCannotConnectToHost,
        NSURLErrorNetworkConnectionLost -> "Не удалось подключиться к серверу"

        NSURLErrorSecureConnectionFailed,
        NSURLErrorServerCertificateHasBadDate,
        NSURLErrorServerCertificateUntrusted,
        NSURLErrorServerCertificateHasUnknownRoot,
        NSURLErrorServerCertificateNotYetValid,
        NSURLErrorClientCertificateRejected,
        NSURLErrorClientCertificateRequired,
        NSURLErrorAppTransportSecurityRequiresSecureConnection -> "Ошибка защищённого соединения с сервером"

        else -> null
    }
}
