package com.example.pogopvp

import android.content.Context
import org.json.JSONArray

val FORM_KO = mapOf("alolan" to "알로라", "galarian" to "가라르", "hisuian" to "히스이", "paldean" to "팔데아")
val TYPE_KO = mapOf(
    "normal" to "노말", "fire" to "불꽃", "water" to "물", "electric" to "전기", "grass" to "풀", "ice" to "얼음",
    "fighting" to "격투", "poison" to "독", "ground" to "땅", "flying" to "비행", "psychic" to "에스퍼", "bug" to "벌레",
    "rock" to "바위", "ghost" to "고스트", "dragon" to "드래곤", "dark" to "악", "steel" to "강철", "fairy" to "페어리"
)

/** rank, moves: 인덱스 0=슈퍼(1500) 1=하이퍼(2500) 2=마스터(10000). moves = PvPoke 추천 기술세트의 타입들 */
class Mon(val id: String, val base: String, val form: String, val types: List<String>, val rank: List<Int>, val moves: List<List<String>>) {
    val name get() = (FORM_KO[form]?.let { "$it " } ?: "") + base
}

object Dex {
    private var all: List<Mon> = emptyList()
    private var bases: List<String> = emptyList()

    fun load(ctx: Context) {
        if (all.isNotEmpty()) return
        val arr = JSONArray(ctx.assets.open("pokemon.json").bufferedReader().readText())
        all = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            fun strs(a: JSONArray) = (0 until a.length()).map { i -> a.getString(i) }
            Mon(o.getString("id"), o.getString("b"), o.getString("f"), strs(o.getJSONArray("t")),
                (0 until 3).map { i -> o.getJSONArray("r").getInt(i) },
                (0 until 3).map { i -> strs(o.getJSONArray("m").getJSONArray(i)) })
        }
        bases = all.map { it.base }.distinct().sortedByDescending { it.length }
    }

    /** OCR로 읽은 한 줄에서 포켓몬을 찾는다. 이름 줄은 짧다는 점을 이용해 일반 문장은 걸러낸다. */
    fun find(raw: String): Mon? {
        val norm = raw.filter { it.isLetterOrDigit() }
        for (b in bases) {
            val ok = if (b.length == 1) norm == b else norm.contains(b) && norm.length <= b.length + 8
            if (!ok) continue
            val c = all.filter { it.base == b }
            return c.firstOrNull { it.form.isNotEmpty() && norm.contains(FORM_KO[it.form]!!) }
                ?: c.firstOrNull { it.form.isEmpty() && !it.id.contains('_') } ?: c.first()
        }
        return null
    }
}

object Matchup {
    // 공격 타입 -> (효과 좋음, 별로, 효과 없음)
    private val C: Map<String, Triple<List<String>, List<String>, List<String>>> = mapOf(
        "normal" to Triple(listOf(), listOf("rock", "steel"), listOf("ghost")),
        "fire" to Triple(listOf("grass", "ice", "bug", "steel"), listOf("fire", "water", "rock", "dragon"), listOf()),
        "water" to Triple(listOf("fire", "ground", "rock"), listOf("water", "grass", "dragon"), listOf()),
        "electric" to Triple(listOf("water", "flying"), listOf("electric", "grass", "dragon"), listOf("ground")),
        "grass" to Triple(listOf("water", "ground", "rock"), listOf("fire", "grass", "poison", "flying", "bug", "dragon", "steel"), listOf()),
        "ice" to Triple(listOf("grass", "ground", "flying", "dragon"), listOf("fire", "water", "ice", "steel"), listOf()),
        "fighting" to Triple(listOf("normal", "ice", "rock", "dark", "steel"), listOf("poison", "flying", "psychic", "bug", "fairy"), listOf("ghost")),
        "poison" to Triple(listOf("grass", "fairy"), listOf("poison", "ground", "rock", "ghost"), listOf("steel")),
        "ground" to Triple(listOf("fire", "electric", "poison", "rock", "steel"), listOf("grass", "bug"), listOf("flying")),
        "flying" to Triple(listOf("grass", "fighting", "bug"), listOf("electric", "rock", "steel"), listOf()),
        "psychic" to Triple(listOf("fighting", "poison"), listOf("psychic", "steel"), listOf("dark")),
        "bug" to Triple(listOf("grass", "psychic", "dark"), listOf("fire", "fighting", "poison", "flying", "ghost", "steel", "fairy"), listOf()),
        "rock" to Triple(listOf("fire", "ice", "flying", "bug"), listOf("fighting", "ground", "steel"), listOf()),
        "ghost" to Triple(listOf("psychic", "ghost"), listOf("dark"), listOf("normal")),
        "dragon" to Triple(listOf("dragon"), listOf("steel"), listOf("fairy")),
        "dark" to Triple(listOf("psychic", "ghost"), listOf("fighting", "dark", "fairy"), listOf()),
        "steel" to Triple(listOf("ice", "rock", "fairy"), listOf("fire", "water", "electric", "steel"), listOf()),
        "fairy" to Triple(listOf("fighting", "dragon", "dark"), listOf("fire", "poison", "steel"), listOf())
    )

    // 포켓몬고 PvP 배율: 효과 좋음 1.6 / 별로 0.625 / 효과 없음 0.390625
    private fun mult(a: String, d: String): Double {
        val c = C[a] ?: return 1.0
        return if (d in c.first) 1.6 else if (d in c.second) 0.625 else if (d in c.third) 0.390625 else 1.0
    }

    private fun attackTypes(m: Mon, lg: Int) = m.moves[lg].ifEmpty { m.types }
    private fun best(att: List<String>, def: List<String>) =
        att.map { a -> a to def.fold(1.0) { p, d -> p * mult(a, d) } }.maxByOrNull { it.second }!!
    private fun ko(ts: List<String>) = ts.joinToString("/") { TYPE_KO[it] ?: it }

    fun report(opp: Mon?, me: Mon?, lg: Int): String {
        val ln = listOf("슈퍼", "하이퍼", "마스터")[lg]
        if (opp == null || me == null) return "[$ln리그] 화면에서 두 포켓몬 이름을 찾는 중…\n(이 창을 탭하면 리그 변경)"
        val a = best(attackTypes(opp, lg), me.types)
        val b = best(attackTypes(me, lg), opp.types)
        val v = when {
            a.second - b.second > 0.01 -> "⚠ 상대가 타입상 유리"
            b.second - a.second > 0.01 -> "✔ 내가 타입상 유리"
            else -> "타입상 비슷"
        }
        return "[$ln리그] (탭: 리그 변경)\n" +
            "상대 ${opp.name} ${ko(opp.types)}\n  기술 ${ko(attackTypes(opp, lg))}\n" +
            "나 ${me.name} ${ko(me.types)}\n  기술 ${ko(attackTypes(me, lg))}\n" +
            "상대→나 ×%.2f (${TYPE_KO[a.first]})\n나→상대 ×%.2f (${TYPE_KO[b.first]})\n$v".format(a.second, b.second)
    }
}
