package com.hopa.capabilities.backend

/**
 * Why a pairing or refresh call failed. A refusal means the gateway answered
 * and said no (the tokens are dead); [Unavailable] means the answer never
 * arrived, so the stored identity must survive and the call may be retried.
 */
sealed class BackendPairingFailure(message: String) : IllegalStateException(message) {
    /** The gateway answered with a 4xx: the token or session is refused. */
    class Refused(val status: Int, message: String) : BackendPairingFailure(message)

    /** No answer, a 5xx, or an incomplete body: retry later. */
    class Unavailable(message: String) : BackendPairingFailure(message)
}
