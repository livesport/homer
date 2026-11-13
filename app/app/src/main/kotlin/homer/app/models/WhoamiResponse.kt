package homer.app.models

import kotlinx.serialization.Serializable

/** Body of `GET /api/v1/callback/whoami`: the run holding the presented callback token. See ADR-0110. */
@Serializable
data class WhoamiResponse(
    val processId: Int,
    val agent: String,
)
