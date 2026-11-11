package at.b05.holopix.data

import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

data class BringList(val uuid: String, val name: String)
data class ShopItem(val name: String, val spec: String)
data class ShopState(val purchase: List<ShopItem>, val recently: List<ShopItem>)

/**
 * Bring! Einkaufslisten über die (inoffizielle) Bring!-REST-API,
 * dieselbe, die auch die Bring!-Web-App und die Home-Assistant-Integration nutzen.
 */
class BringApi(private val prefs: Prefs) {

    companion object {
        private const val BASE = "https://api.getbring.com/rest"
        private const val API_KEY = "cof4Nc6D8saplXjE3h3HXqHH8m7VU2i1Gs0g85Sp"
    }

    private fun Request.Builder.bringHeaders(): Request.Builder = this
        .header("X-BRING-API-KEY", API_KEY)
        .header("X-BRING-CLIENT", "webApp")
        .header("X-BRING-CLIENT-SOURCE", "webApp")
        .header("X-BRING-COUNTRY", "AT")
        .apply {
            if (prefs.bringToken.isNotBlank()) {
                header("Authorization", "Bearer ${prefs.bringToken}")
                header("X-BRING-USER-UUID", prefs.bringUserUuid)
            }
        }

    suspend fun login(email: String = prefs.bringEmail, password: String = prefs.bringPassword) {
        val body = FormBody.Builder().add("email", email).add("password", password).build()
        val res = try {
            Request.Builder().url("$BASE/v2/bringauth").post(body).bringHeaders().build().fetch()
        } catch (e: HttpException) {
            throw IOException(if (e.code == 401 || e.code == 404) "Bring!-Login fehlgeschlagen (E-Mail/Passwort prüfen)" else e.message)
        }
        val o = JSONObject(res)
        prefs.bringEmail = email
        prefs.bringPassword = password
        prefs.bringToken = o.getString("access_token")
        prefs.bringUserUuid = o.getString("uuid")
        if (prefs.bringListUuid.isBlank()) prefs.bringListUuid = o.optString("bringListUUID")
    }

    fun logout() {
        prefs.bringEmail = ""; prefs.bringPassword = ""; prefs.bringToken = ""
        prefs.bringUserUuid = ""; prefs.bringListUuid = ""; prefs.bringListName = ""
    }

    private suspend fun withAuth(build: () -> Request): String {
        if (prefs.bringToken.isBlank()) login()
        return try {
            build().fetch()
        } catch (e: HttpException) {
            if (e.code == 401) {
                login(); build().fetch()
            } else throw e
        }
    }

    suspend fun lists(): List<BringList> {
        val res = withAuth { Request.Builder().url("$BASE/bringusers/${prefs.bringUserUuid}/lists").bringHeaders().build() }
        val arr = JSONObject(res).optJSONArray("lists") ?: return emptyList()
        return (0 until arr.length()).map {
            val l = arr.getJSONObject(it)
            BringList(l.getString("listUuid"), l.optString("name"))
        }
    }

    suspend fun items(listUuid: String = prefs.bringListUuid): ShopState {
        val res = withAuth { Request.Builder().url("$BASE/v2/bringlists/$listUuid").bringHeaders().build() }
        val o = JSONObject(res)
        val items = o.optJSONObject("items")
        fun parse(key: String): List<ShopItem> {
            val a = items?.optJSONArray(key) ?: o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map {
                val i = a.getJSONObject(it)
                ShopItem(i.optString("itemId", i.optString("name")), i.optString("specification"))
            }
        }
        return ShopState(parse("purchase"), parse("recently"))
    }

    private suspend fun put(listUuid: String, form: String) {
        withAuth {
            Request.Builder().url("$BASE/v2/bringlists/$listUuid")
                .put(form.toRequestBody(Http.FORM))
                .bringHeaders().build()
        }
    }

    private fun e(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun add(name: String, spec: String = "", listUuid: String = prefs.bringListUuid) =
        put(listUuid, "uuid=${e(listUuid)}&purchase=${e(name)}&specification=${e(spec)}")

    /** Abhaken: Artikel wandert nach "Zuletzt verwendet". */
    suspend fun complete(name: String, listUuid: String = prefs.bringListUuid) =
        put(listUuid, "uuid=${e(listUuid)}&recently=${e(name)}&specification=")

    suspend fun remove(name: String, listUuid: String = prefs.bringListUuid) =
        put(listUuid, "uuid=${e(listUuid)}&remove=${e(name)}")
}
