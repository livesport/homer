package homer.runner

import homer.app.models.RunnerTagValidation

/**
 * The version this runner binary was built from, as the build stamped it into the jar
 * (`homerVersion` Gradle property, else `HOMER_VERSION`, else `dev` — one source, see
 * `homer-runner/build.gradle.kts`). Homer validates the registration's `version` against
 * [RunnerTagValidation.PATTERN] and reports it to operators, so that same shape is the one
 * gate here — a jar that passes this parses would be refused `422` at registration otherwise.
 * The value is never read from run-time configuration: every host running the same jar
 * reports the same thing.
 */
object RunnerVersion {
    /** What a build without release information reports. */
    const val FALLBACK = "dev"

    private const val RESOURCE = "/homer/runner/runner-version.txt"

    /** The version the build stamped into this jar; [FALLBACK] when the resource is absent or unusable. */
    val current: String by lazy { parse(resourceText()) }

    /** [raw] trimmed when it is a safe release token, [FALLBACK] otherwise. */
    fun parse(raw: String?): String {
        val version = raw?.trim().orEmpty()
        return if (RunnerTagValidation.PATTERN.matches(version)) version else FALLBACK
    }

    private fun resourceText(): String? = RunnerVersion::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
}
