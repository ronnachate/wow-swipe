package com.wow.wswipe.engine

/**
 * Suggests e-mail addresses, Gboard-style:
 *  - addresses you have typed before, as soon as you start typing one;
 *  - after "@": your usual domains, then common ones (gmail.com first).
 */
class EmailSuggester {
    private val emails = LinkedHashMap<String, Int>()
    private val removedDomains = HashSet<String>()

    /** Number of saved addresses. */
    val size get() = emails.size

    fun suggest(token: String, limit: Int = 3): List<String> {
        if (token.isEmpty() || token.any { it.isWhitespace() }) return emptyList()
        val t = token.lowercase()
        val out = ArrayList<String>()
        // 1. Saved addresses starting with what's typed.
        emails.entries
            .filter { it.key.startsWith(t) && it.key != t }
            .sortedByDescending { it.value }
            .forEach { out += it.key }
        // 2. Domain completion after "@".
        val at = token.indexOf('@')
        if (at > 0 && token.indexOf('@', at + 1) < 0) {
            val local = token.substring(0, at)
            val typedDomain = t.substring(at + 1)
            val learned = emails.entries.groupBy({ it.key.substringAfter('@') }, { it.value })
                .mapValues { it.value.sum() }.entries.sortedByDescending { it.value }.map { it.key }
            for (d in learned + COMMON_DOMAINS) {
                if (d in removedDomains) continue
                if (d.startsWith(typedDomain) && d != typedDomain) out += "$local@$d"
            }
        }
        return out.distinct().take(limit)
    }

    /** Remember a finished address (ignored if it doesn't look like one). */
    fun learn(text: String): Boolean {
        val e = text.trim().trimEnd('.', ',', ';', ':', ')', '>').lowercase()
        if (!EMAIL.matches(e)) return false
        emails[e] = (emails[e] ?: 0) + 1
        return true
    }

    /** Forget a suggestion the user long-pressed. */
    fun forget(suggestion: String) {
        val s = suggestion.lowercase()
        if (emails.remove(s) == null) {
            // It was a domain completion: stop offering that domain.
            removedDomains += s.substringAfter('@')
        }
    }

    fun serialize(): String =
        (emails.entries.map { "${it.key}\t${it.value}" } + removedDomains.map { "-\t$it" }).joinToString("\n")

    fun load(text: String) {
        for (line in text.lineSequence()) {
            val p = line.split('\t')
            if (p.size != 2) continue
            if (p[0] == "-") removedDomains += p[1]
            else p[1].toIntOrNull()?.let { emails[p[0]] = it }
        }
    }

    companion object {
        val COMMON_DOMAINS = listOf("gmail.com", "hotmail.com", "outlook.com", "yahoo.com", "icloud.com")
        private val EMAIL = Regex("^[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}$")

        /** Characters that can be part of an address token. */
        fun isEmailChar(c: Char) = c.isLetterOrDigit() || c in "._%+-@"
    }
}
