package homer.runner

/**
 * The version this runner binary was built from, as the build stamped it into the jar
 * (`homerVersion` Gradle property, else `HOMER_VERSION`, else `dev` — one source, see
 * `homer-runner/build.gradle.kts`). Homer reports it to operators, so it is held to the
 * shape of an instance name and never read from run-time configuration: every host
 * running the same jar reports the same value.
 */
object RunnerVersion {
    /** The shape a reported version must have — the token shape a release tag and a runner tag use. */
    val PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    /** What a build without release information reports. */
    const val FALLBACK = "dev"

    private const val RESOURCE = "/homer/runner/runner-version.txt"

    /** The version the build stamped into this jar; [FALLBACK] when the resource is absent or unusable. */
    val current: String by lazy { parse(resourceText()) }

    /** [raw] trimmed when it is a safe release token, [FALLBACK] otherwise. */
    fun parse(raw: String?): String {
        val version = raw?.trim().orEmpty()
        return if (PATTERN.matches(version)) version else FALLBACK
    }

    private fun resourceText(): String? = RunnerVersion::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
}
