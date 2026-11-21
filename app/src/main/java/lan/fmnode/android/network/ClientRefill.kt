package lan.fmnode.android.network

/** User-visible state of the multi-device mailbox refill (#56). */
enum class ClientRefillStatus {
    Idle,
    Waiting,
    Receiving,
    Done,
    Partial,
    NoClients,
    Error,
}

data class ClientRefillUi(
    val status: ClientRefillStatus = ClientRefillStatus.Idle,
    val expected: Int = 0,
    val done: Int = 0,
    val messages: Int = 0,
    /** Machine-readable reason for the Error status (`disabled`, `queue`, ...). */
    val error: String = "",
) {
    val active: Boolean
        get() = status == ClientRefillStatus.Waiting || status == ClientRefillStatus.Receiving
}
