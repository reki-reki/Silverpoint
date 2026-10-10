package app.silverpoint.recipes

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Loads the recipe list from `recipes.json`, keeping the file's order. */
object RecipeStore {
    const val ASSET = "recipes.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): List<Recipe> =
        context.assets.open(ASSET).use { parse(it.readBytes().decodeToString()) }

    fun parse(text: String): List<Recipe> =
        json.decodeFromString<Map<String, RecipeEntry>>(text).map { (name, e) ->
            val p = e.params
            for ((key, curve) in listOf("curve" to p.curve, "curve_red" to p.curveRed, "curve_green" to p.curveGreen, "curve_blue" to p.curveBlue)) {
                require(curve.all { it.size == 2 }) { "$name: $key points must be [input, output]" }
                // The look's spline needs at least two points with strictly increasing inputs.
                require(curve.size >= 2 && curve.zipWithNext().all { (a, b) -> b[0] > a[0] }) { "$name: $key inputs must increase" }
            }
            Recipe(name, e.file, e.set, e.group, e.baseSim, e.reference, e.look, e.bestFor, e.params)
        }

    @Serializable
    private data class RecipeEntry(
        val file: String = "",
        val set: String = "",
        val group: String = "",
        @SerialName("base_sim") val baseSim: String = "",
        val reference: String = "",
        val look: String = "",
        @SerialName("best_for") val bestFor: String = "",
        val params: RecipeParams = RecipeParams(),
    )
}
