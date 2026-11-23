package com.example.data.security

/** HTTP outcomes that invalidate the locally cached device-bound session. */
internal object SessionResponsePolicy {
    fun shouldClearAfterAuthenticatedRequest(statusCode: Int): Boolean = statusCode == 401

    fun shouldClearAfterLogout(statusCode: Int): Boolean =
        statusCode in 200..299 || statusCode == 401
}
