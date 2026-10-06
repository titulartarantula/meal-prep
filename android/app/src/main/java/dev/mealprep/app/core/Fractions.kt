package dev.mealprep.app.core

/** "1 3/4 cups" → "1¾ cups", as the shopping list writes amounts. Only fractions with a glyph are changed; dates,
 *  oven temperatures ("350/180") and other slashes stay as they are. */
object Fractions {
    private val GLYPHS = mapOf(
        "1/2" to "½", "1/3" to "⅓", "2/3" to "⅔", "1/4" to "¼", "3/4" to "¾", "1/5" to "⅕", "2/5" to "⅖", "3/5" to "⅗",
        "4/5" to "⅘", "1/6" to "⅙", "5/6" to "⅚", "1/8" to "⅛", "3/8" to "⅜", "5/8" to "⅝", "7/8" to "⅞",
    )
    private val MIXED = Regex("""(?<![\d/.])(\d+)\s+(\d/\d)(?![\d/])""")
    private val SIMPLE = Regex("""(?<![\d/.])(\d/\d)(?![\d/])""")

    fun pretty(text: String): String {
        val mixed = MIXED.replace(text) { m -> GLYPHS[m.groupValues[2]]?.let { m.groupValues[1] + it } ?: m.value }
        return SIMPLE.replace(mixed) { m -> GLYPHS[m.groupValues[1]] ?: m.value }
    }
}
