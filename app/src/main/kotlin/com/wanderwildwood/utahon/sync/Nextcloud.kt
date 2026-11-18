package com.wanderwildwood.utahon.sync

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A Nextcloud account as its login page hands it over: the server, the user's login name and an
 * app password made for this phone alone, which can be revoked from the server's security
 * settings without touching the real one.
 */
data class Account(val server: String, val user: String, val password: String) {
    fun toJson(): JSONObject = JSONObject().put("server", server).put("user", user).put("password", password)

    companion object {
        fun fromJson(o: JSONObject) = Account(o.getString("server"), o.getString("user"), o.getString("password"))
    }
}

/**
 * What to do with the stored account text: the sealed form is opened; the plain JSON an older
 * version wrote is read as it is and handed back sealed ([migrated]) so the caller can replace it
 * once. [seal] and [open] are the keystore's, passed in so this part can be tested on its own.
 */
data class StoredAccount(val account: Account?, val migrated: String?)

fun readStoredAccount(stored: String?, seal: (String) -> String?, open: (String) -> String?): StoredAccount {
    if (stored.isNullOrEmpty()) return StoredAccount(null, null)
    val json = open(stored) ?: return StoredAccount(null, null)
    val account = runCatching { Account.fromJson(JSONObject(json)) }.getOrNull() ?: return StoredAccount(null, null)
    val plainBefore = stored.trimStart().startsWith("{")
    return StoredAccount(account, if (plainBefore) seal(json) else null)
}

/** The server could not be reached, or its certificate could not be trusted: nothing was sent. */
class Unreachable(message: String) : IOException(message)

/** The server would not let this account in: signed out on the server, or a wrong password. */
class Refused(message: String) : IOException(message)

internal const val AGENT = "Songbook (utahon)"

internal val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

/**
 * Nextcloud's own sign-in, the one its apps use: the phone asks the server for a login page,
 * the reader signs in there in the browser, and the server hands this phone an app password.
 * Nobody types a password into this app that way, and two-factor works because it is the
 * server's own page. A phone with no browser takes an app password typed in instead.
 */
object LoginFlow {

    data class Started(val loginUrl: String, val pollUrl: String, val token: String)

    fun start(server: String): Started {
        if (isPlain(server)) throw Refused("needs https")
        val request = Request.Builder()
            .url("${normalise(server)}/index.php/login/v2")
            .post(FormBody.Builder().build())
            .header("User-Agent", AGENT)
            .build()
        val json = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Refused("${response.code}")
                JSONObject(response.body!!.string())
            }
        } catch (e: IOException) {
            if (e is Refused) throw e
            throw Unreachable(e.message ?: "no answer")
        }
        val poll = json.getJSONObject("poll")
        return checked(Started(json.getString("login"), poll.getString("endpoint"), poll.getString("token")), normalise(server))
            ?: throw Refused("login addresses")
    }

    /**
     * The server's two addresses, made safe to use, or null when they are not.
     *
     * The login page opens in the browser, so it must be a web address; the poll address gets
     * the token that fetches the app password, so it must be https. Each may name the server
     * by another host than the one typed here (a server behind a proxy often does), and is
     * taken as it is when it is https. One that says http on the very host typed here is the
     * same proxy forgetting it is behind https, and is taken as https. Anything else is refused.
     * Tolerant on purpose: it was not tried against every kind of server.
     */
    internal fun checked(started: Started, server: String): Started? {
        val host = hostOf(server) ?: return null
        val login = safe(started.loginUrl, host) ?: return null
        val poll = safe(started.pollUrl, host) ?: return null
        return started.copy(loginUrl = login, pollUrl = poll)
    }

    private fun safe(url: String, typedHost: String): String? {
        val u = url.trim()
        return when {
            u.startsWith("https://", ignoreCase = true) && hostOf(u) != null -> u
            u.startsWith("http://", ignoreCase = true) && hostOf(u).equals(typedHost, ignoreCase = true) -> "https://" + u.substring("http://".length)
            else -> null
        }
    }

    /** The host of an address ("cloud.example" of "https://cloud.example:8443/x"), or null. */
    private fun hostOf(url: String): String? {
        val rest = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        val host = rest.substringAfterLast('@').let { if (it.startsWith("[")) it.substringBefore(']') + "]" else it.substringBefore(':') }
        return host.takeIf { it.isNotBlank() }
    }

    /**
     * The account, once the reader has finished in the browser, or null while they have not.
     * [Unreachable] when the server did not answer this time.
     */
    fun poll(started: Started): Account? {
        val request = Request.Builder()
            .url(started.pollUrl)
            .post(FormBody.Builder().add("token", started.token).build())
            .header("User-Agent", AGENT)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (response.code == 404) return null
                if (!response.isSuccessful) throw Refused("${response.code}")
                val json = JSONObject(response.body!!.string())
                Account(
                    server = json.getString("server").trimEnd('/'),
                    user = json.getString("loginName"),
                    password = json.getString("appPassword"),
                )
            }
        } catch (e: IOException) {
            if (e is Refused) throw e
            // Said to the caller, who counts them: a hand-over that keeps failing is not "still waiting".
            throw Unreachable(e.message ?: "no answer")
        }
    }

    /** What was typed, as an address: "cloud.example" becomes "https://cloud.example". */
    fun normalise(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        return if (trimmed.startsWith("https://", ignoreCase = true) || isPlain(trimmed)) trimmed else "https://$trimmed"
    }

    /** True when what was typed names plain http, which this app never uses. */
    fun isPlain(server: String): Boolean = server.trim().startsWith("http://", ignoreCase = true)
}
