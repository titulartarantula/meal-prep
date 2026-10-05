package dev.mealprep.app.data.api

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** PATCH bodies built by hand: the server treats every key present as "change this", and null must be sent explicitly. */
object Bodies {
    fun entryPatch(day: Int? = null, unplace: Boolean = false, multiplier: Double? = null): JsonObject = buildJsonObject {
        if (unplace) put("day", JsonNull) else if (day != null) put("day", day)
        if (multiplier != null) put("multiplier", multiplier)
    }

    /** Staple edit: only what is given; with [amount] qty and unit are sent even when null (null clears them). */
    fun staplePatch(name: String? = null, qty: Double? = null, unit: String? = null, weekly: Boolean? = null,
                    amount: Boolean = false, position: Int? = null): JsonObject = buildJsonObject {
        if (name != null) put("name", name)
        if (amount) { if (qty != null) put("qty", qty) else put("qty", JsonNull); if (unit != null) put("unit", unit) else put("unit", JsonNull) }
        if (weekly != null) put("weekly", weekly)
        if (position != null) put("position", position)
    }

    fun linePatch(productCode: String? = null, quantity: Int? = null, removed: Boolean? = null): JsonObject = buildJsonObject {
        if (productCode != null) put("product_code", productCode)
        if (quantity != null) put("quantity", quantity)
        if (removed != null) put("removed", removed)
    }
}
