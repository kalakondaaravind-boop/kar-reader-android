package com.kar.reader.core.domain

/** Facts about one book that smart collection rules can test. */
data class BookFacts(
    val format: String,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val progressPercent: Int,
    val highlightCount: Int,
    val noteCount: Int,
    val rating: Int,
    val favorite: Boolean,
    val tags: Set<String>,
    val author: String,
)

enum class RuleField(val label: String) {
    FORMAT("Format"), ADDED_DAYS("Added"), OPENED_DAYS("Last opened"), PROGRESS("Progress"),
    HIGHLIGHTS("Highlights"), NOTES("Notes"), RATING("Rating"), FAVORITE("Favorite"), TAG("Tag"), AUTHOR("Author"),
}

enum class RuleOp(val label: String) {
    IS("is"), IS_NOT("is not"), CONTAINS("contains"),
    LESS_THAN("is below"), MORE_THAN("is above"),
    WITHIN_DAYS("in the last"), OLDER_THAN_DAYS("more than"),
}

data class SmartRule(val field: RuleField, val op: RuleOp, val value: String)

data class SmartQuery(val rules: List<SmartRule>, val matchAll: Boolean = true) {

    fun matches(book: BookFacts, now: Long): Boolean {
        if (rules.isEmpty()) return true
        return if (matchAll) rules.all { test(it, book, now) } else rules.any { test(it, book, now) }
    }

    /** Compact storage format: "all|FIELD:OP:value;FIELD:OP:value". Values are percent-escaped. */
    fun encode(): String = (if (matchAll) "all" else "any") + "|" +
        rules.joinToString(";") { "${it.field.name}:${it.op.name}:${escape(it.value)}" }

    companion object {
        /** Operators that make sense for each field, in menu order. */
        fun opsFor(field: RuleField): List<RuleOp> = when (field) {
            RuleField.FORMAT, RuleField.FAVORITE -> listOf(RuleOp.IS, RuleOp.IS_NOT)
            RuleField.TAG, RuleField.AUTHOR -> listOf(RuleOp.IS, RuleOp.CONTAINS, RuleOp.IS_NOT)
            RuleField.ADDED_DAYS, RuleField.OPENED_DAYS -> listOf(RuleOp.WITHIN_DAYS, RuleOp.OLDER_THAN_DAYS)
            RuleField.PROGRESS, RuleField.HIGHLIGHTS, RuleField.NOTES, RuleField.RATING ->
                listOf(RuleOp.IS, RuleOp.LESS_THAN, RuleOp.MORE_THAN)
        }

        fun decode(encoded: String?): SmartQuery? {
            if (encoded.isNullOrBlank()) return null
            val mode = encoded.substringBefore('|')
            val body = encoded.substringAfter('|', "")
            val rules = body.split(';').filter { it.isNotBlank() }.mapNotNull { part ->
                val pieces = part.split(':', limit = 3)
                if (pieces.size != 3) return@mapNotNull null
                val f = RuleField.entries.firstOrNull { it.name == pieces[0] } ?: return@mapNotNull null
                val o = RuleOp.entries.firstOrNull { it.name == pieces[1] } ?: return@mapNotNull null
                SmartRule(f, o, unescape(pieces[2]))
            }
            return SmartQuery(rules, matchAll = mode != "any")
        }

        private fun escape(s: String) = s.replace("%", "%25").replace(";", "%3B").replace("|", "%7C").replace(":", "%3A")
        private fun unescape(s: String) = s.replace("%3A", ":").replace("%7C", "|").replace("%3B", ";").replace("%25", "%")

        private const val DAY = 86_400_000L

        private fun test(rule: SmartRule, b: BookFacts, now: Long): Boolean {
            val v = rule.value.trim()
            return when (rule.field) {
                RuleField.FORMAT -> eq(rule.op, b.format.equals(v, ignoreCase = true))
                RuleField.FAVORITE -> eq(rule.op, b.favorite == (v.lowercase() in setOf("yes", "true", "1")))
                RuleField.TAG -> when (rule.op) {
                    RuleOp.IS -> b.tags.any { it.equals(v, true) }
                    RuleOp.IS_NOT -> b.tags.none { it.equals(v, true) }
                    RuleOp.CONTAINS -> b.tags.any { it.contains(v, true) }
                    else -> false
                }
                RuleField.AUTHOR -> when (rule.op) {
                    RuleOp.IS -> b.author.equals(v, true)
                    RuleOp.IS_NOT -> !b.author.equals(v, true)
                    RuleOp.CONTAINS -> b.author.contains(v, true)
                    else -> false
                }
                RuleField.ADDED_DAYS -> days(rule.op, b.addedAt, v, now)
                RuleField.OPENED_DAYS -> {
                    val opened = b.lastOpenedAt
                    if (opened == null) rule.op == RuleOp.OLDER_THAN_DAYS else days(rule.op, opened, v, now)
                }
                RuleField.PROGRESS -> num(rule.op, b.progressPercent, v)
                RuleField.HIGHLIGHTS -> num(rule.op, b.highlightCount, v)
                RuleField.NOTES -> num(rule.op, b.noteCount, v)
                RuleField.RATING -> num(rule.op, b.rating, v)
            }
        }

        private fun eq(op: RuleOp, same: Boolean) = if (op == RuleOp.IS_NOT) !same else same

        private fun num(op: RuleOp, actual: Int, v: String): Boolean {
            val target = v.trimEnd('%').toIntOrNull() ?: return false
            return when (op) {
                RuleOp.IS -> actual == target
                RuleOp.IS_NOT -> actual != target
                RuleOp.LESS_THAN -> actual < target
                RuleOp.MORE_THAN -> actual > target
                else -> false
            }
        }

        private fun days(op: RuleOp, time: Long, v: String, now: Long): Boolean {
            val d = v.filter { it.isDigit() }.toLongOrNull() ?: return false
            val age = now - time
            return when (op) {
                RuleOp.WITHIN_DAYS -> age <= d * DAY
                RuleOp.OLDER_THAN_DAYS -> age > d * DAY
                else -> false
            }
        }
    }
}
