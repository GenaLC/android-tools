package com.callagent.gateway.update

/**
 * One version against another.
 *
 * The release feed says which version is newest, but the decision — is that
 * newer than the one running here? — has to be made without a phone in hand,
 * so it lives in plain code that can be tested.
 */
object AppVersion {

    /**
     * The version without the tag's 'v' and without any suffix.
     *
     * The feed carries tags like `v1.4.27`, the application carries
     * `1.4.27`, and a pre-release carries both: `v1.4.27-beta`.
     */
    fun normalize(raw: String): String =
        raw.trim()
            .removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')

    /**
     * The version's numbers, most significant first.
     *
     * A part that is not a number counts as zero: `1.4.x` is not a version
     * anyone can compare, and treating it as `1.4.0` keeps the comparison
     * going instead of failing on it.
     */
    fun parts(raw: String): List<Int> =
        normalize(raw).split('.').map { it.toIntOrNull() ?: 0 }

    /**
     * Whether the candidate is a newer version than the current one.
     *
     * The numbers are compared by place, not by length: `1.5` is newer than
     * `1.4.26`, and `1.4` is older than `1.4.1`.  A version that is not there
     * is never newer — there would be nothing to compare, and installing
     * something on a guess is not a thing to do.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val c = normalize(candidate)
        val n = normalize(current)
        if (c.isEmpty() || n.isEmpty()) return false
        val cp = parts(c)
        val np = parts(n)
        for (i in 0 until maxOf(cp.size, np.size)) {
            val a = cp.getOrElse(i) { 0 }
            val b = np.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * Whether a release asset is the application itself.
     *
     * A release carries two files: the application and the Magisk module
     * that carries it.  Only the first one can be installed over the
     * running application.
     */
    fun isApkAsset(name: String): Boolean = name.endsWith(".apk", ignoreCase = true)
}
