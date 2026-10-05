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

    fun linePatch(productCode: String? = null, quantity: Int? = null, removed: Boolean? = null): JsonObject = buildJsonObject {
        if (productCode != null) put("product_code", productCode)
        if (quantity != null) put("quantity", quantity)
        if (removed != null) put("removed", removed)
    }
}
