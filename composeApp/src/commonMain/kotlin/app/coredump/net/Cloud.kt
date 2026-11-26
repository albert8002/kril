package app.coredump.net

import app.coredump.platform.CrashLog
import app.coredump.platform.Platform
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Accounts (Supabase: e-mail + password sign-in, a small Postgres behind a REST API). Everything goes over plain
 * HTTPS, so the phone apps and the web app share it. The address and the public ("anon") key come from
 * files/cloud.json (set once by whoever runs the server; the key is public by design — what each account may read
 * and write is enforced by the database, see supabase/schema.sql in the repository).
 */
object Cloud {
    /** The web app's address: sign-up and password-reset e-mails link back to it. */
    const val SITE = "https://albert8002.github.io/kril/"
    private const val SESSION_FILE = "cloud_session.json"

    var url = ""
        private set
    var key = ""
        private set
    /** Address and key are set (accounts can be used). */
    val configured: Boolean get() = url.startsWith("https://") && key.length > 20

    /** The signed-in account, null when signed out. */
    var session: CloudSession? = null
        private set
    private val refreshLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient {
        install(WebSafe)
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 12_000
        }
        expectSuccess = false
    }

    /** files/cloud.json: {"url": "https://….supabase.co", "key": "<anon key>"}; also the saved session. */
    fun configure(configJson: String?) {
        runCatching {
            val o = json.parseToJsonElement(configJson ?: return@runCatching).obj()
            url = o.str("url").trim().trimEnd('/')
            key = o.str("key").trim()
        }
        if (session == null) session = runCatching { CloudSession.decode(Platform.readText(SESSION_FILE)) }.getOrNull()
        pendingLink?.let { link -> if (link.session != null) setSession(link.session) }
    }

    // ------------------------------------------------------------------ links from e-mails (web app)

    /** What an e-mail link brought (sign-up confirmed, password reset, or an error), read once at start. */
    var pendingLink: CloudLink? = null

    /**
     * The web app's start address: an e-mail link from Supabase comes back as …/kril/#access_token=…&refresh_token=…
     * &type=signup|recovery (or #error=…&error_description=…). Returns true when the address carried one (so the
     * caller can clear it from the address bar).
     */
    fun bootLink(href: String): Boolean {
        val frag = href.substringAfter('#', "")
        if (frag.isBlank()) return false
        val q = frag.split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i) to decode(kv.substring(i + 1))
        }.toMap()
        if (q["error"] != null || q["error_description"] != null) {
            pendingLink = CloudLink(null, q["type"].orEmpty(), q["error_description"] ?: q["error"] ?: "link failed")
            return true
        }
        val access = q["access_token"] ?: return false
        val refresh = q["refresh_token"].orEmpty()
        val exp = q["expires_at"]?.toLongOrNull()?.let { it * 1000 }
            ?: (Platform.epochMillis() + (q["expires_in"]?.toLongOrNull() ?: 3600L) * 1000)
        val uid = jwtField(access, "sub").orEmpty()
        val email = jwtField(access, "email").orEmpty()
        if (uid.isBlank()) return false
        pendingLink = CloudLink(CloudSession(access, refresh, exp, uid, email), q["type"].orEmpty(), null)
        return true
    }

    /** Percent-decoding of an address part (UTF-8). */
    private fun decode(s: String): String {
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val v = if (c == '%' && i + 2 <= s.lastIndex) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            when {
                v != null -> { out += v.toByte(); i += 3 }
                c == '+' -> { out += ' '.code.toByte(); i++ }
                else -> { for (b in c.toString().encodeToByteArray()) out += b; i++ }
            }
        }
        return out.toByteArray().decodeToString()
    }

    /** A field of a JWT's payload (no signature check: only used to learn the user id of a token the server made). */
    private fun jwtField(token: String, field: String): String? = runCatching {
        val part = token.split('.')[1].replace('-', '+').replace('_', '/')
        val padded = part + "=".repeat((4 - part.length % 4) % 4)
        val bytes = B64.decode(padded)
        json.parseToJsonElement(bytes.decodeToString()).obj().str(field).ifBlank { null }
    }.getOrNull()

    // ------------------------------------------------------------------ requests

    private class Reply(val status: Int, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    private suspend fun send(
        method: HttpMethod, path: String, body: JsonElement? = null, token: String? = null,
        params: List<Pair<String, String>> = emptyList(), prefer: String? = null,
    ): Reply {
        if (!configured) throw CloudError("accounts are not set up yet")
        val res = http.request(url + path) {
            this.method = method
            for ((k, v) in params) parameter(k, v)
            header("apikey", key)
            // signed out: the legacy anon key (a JWT) goes as the bearer too; the newer "sb_publishable_…" keys must not
            val bearer = token ?: key.takeIf { !it.startsWith("sb_") }
            if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
            if (prefer != null) header("Prefer", prefer)
            if (body != null) setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
        return Reply(res.status.value, res.bodyAsText())
    }

    /** The server's own words for a failed request ("Invalid login credentials", "Password should be …"). */
    private fun reason(r: Reply): String {
        val o = runCatching { json.parseToJsonElement(r.body).obj() }.getOrNull()
        val msg = o?.let { it.str("msg").ifBlank { it.str("error_description") }.ifBlank { it.str("message") }.ifBlank { it.str("error") } }
        return when {
            !msg.isNullOrBlank() -> msg
            r.status == 0 -> "no connection"
            else -> "server said ${r.status}"
        }
    }

    private fun sessionOf(o: JsonObject): CloudSession? {
        val access = o.str("access_token").ifBlank { return null }
        val user = o["user"] as? JsonObject
        val exp = (o["expires_at"] as? JsonPrimitive)?.longOrNull?.let { it * 1000 }
            ?: (Platform.epochMillis() + ((o["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600L) * 1000)
        return CloudSession(access, o.str("refresh_token"), exp, user?.str("id") ?: jwtField(access, "sub").orEmpty(),
            user?.str("email") ?: jwtField(access, "email").orEmpty())
    }

    private fun setSession(s: CloudSession?) {
        session = s
        runCatching { if (s == null) Platform.delete(SESSION_FILE) else Platform.writeText(SESSION_FILE, s.encode()) }
    }

    /** A valid access token (refreshed when it ends within a minute), or null when signed out / refresh refused. */
    private suspend fun token(): String? = refreshLock.withLock {
        val s = session ?: return null
        if (s.expiresAt - Platform.epochMillis() > 60_000) return s.access
        if (s.refresh.isBlank()) { setSession(null); return null }
        val r = runCatching {
            send(HttpMethod.Post, "/auth/v1/token", buildJsonObject { put("refresh_token", s.refresh) },
                params = listOf("grant_type" to "refresh_token"))
        }.getOrElse { return s.access.takeIf { s.expiresAt > Platform.epochMillis() } }
        if (r.ok) {
            val n = sessionOf(json.parseToJsonElement(r.body).obj()) ?: return null
            setSession(n)
            return n.access
        }
        // refresh token revoked or used up: signed out
        if (r.status in 400..499) setSession(null)
        null
    }

    private suspend fun authed(
        method: HttpMethod, path: String, body: JsonElement? = null,
        params: List<Pair<String, String>> = emptyList(), prefer: String? = null,
    ): Reply {
        val t = token() ?: throw CloudError("signed out")
        val r = send(method, path, body, t, params, prefer)
        if (r.status == 401) {
            // the token was refused (expired early, revoked): one refresh and one more try
            session?.let { setSession(it.copy(expiresAt = 0L)) }
            val t2 = token() ?: throw CloudError("signed out")
            return send(method, path, body, t2, params, prefer)
        }
        return r
    }

    private suspend fun rpc(name: String, args: JsonObject = JsonObject(emptyMap())): Reply = authed(HttpMethod.Post, "/rest/v1/rpc/$name", args)

    // ------------------------------------------------------------------ sign-in

    /**
     * New account. Returns true when signed in at once; false when the server first wants the e-mail address
     * confirmed (a link was sent; after it, sign in).
     */
    suspend fun signUp(email: String, password: String, handle: String): Boolean {
        val r = send(HttpMethod.Post, "/auth/v1/signup", buildJsonObject {
            put("email", email.trim())
            put("password", password)
            put("data", buildJsonObject { put("handle", handle) })
        }, params = listOf("redirect_to" to SITE))
        if (!r.ok) throw CloudError(reason(r))
        val s = sessionOf(json.parseToJsonElement(r.body).obj()) ?: return false
        setSession(s)
        return true
    }

    suspend fun signIn(email: String, password: String) {
        val r = send(HttpMethod.Post, "/auth/v1/token", buildJsonObject {
            put("email", email.trim())
            put("password", password)
        }, params = listOf("grant_type" to "password"))
        if (!r.ok) throw CloudError(friendly(reason(r)))
        setSession(sessionOf(json.parseToJsonElement(r.body).obj()) ?: throw CloudError("no session in the answer"))
    }

    private fun friendly(msg: String): String = when {
        msg.contains("Email not confirmed", true) -> "confirm your e-mail address first (the link in the e-mail), then sign in"
        msg.contains("Invalid login", true) -> "wrong e-mail or password"
        else -> msg
    }

    /** Sends a password-reset link (it opens the web app, where a new password is set). */
    suspend fun sendReset(email: String) {
        val r = send(HttpMethod.Post, "/auth/v1/recover", buildJsonObject { put("email", email.trim()) },
            params = listOf("redirect_to" to SITE))
        if (!r.ok) throw CloudError(reason(r))
    }

    suspend fun setPassword(password: String) {
        val r = authed(HttpMethod.Put, "/auth/v1/user", buildJsonObject { put("password", password) })
        if (!r.ok) throw CloudError(reason(r))
    }

    suspend fun signOut() {
        val t = session?.access
        setSession(null)
        if (t != null) runCatching { send(HttpMethod.Post, "/auth/v1/logout", token = t) }
    }

    /** Deletes the account and everything stored with it on the server (this device keeps its own copy). */
    suspend fun deleteAccount() {
        val r = rpc("delete_me")
        if (!r.ok) throw CloudError(reason(r))
        setSession(null)
    }

    // ------------------------------------------------------------------ profile

    suspend fun handleFree(handle: String): Boolean {
        val r = send(HttpMethod.Post, "/rest/v1/rpc/handle_free", buildJsonObject { put("p_handle", handle) })
        if (!r.ok) throw CloudError(reason(r))
        return r.body.trim() == "true"
    }

    /** My profile (made now if the account has none yet, with [handle] as its name). */
    suspend fun profile(handle: String): CloudProfile {
        val r = rpc("ensure_profile", buildJsonObject { put("p_handle", handle) })
        if (!r.ok) throw CloudError(reason(r))
        return CloudProfile.of(json.parseToJsonElement(r.body).let { if (it is JsonArray) it.first() else it }.obj())
    }

    /** "ok", "taken" or "bad". */
    suspend fun setHandle(handle: String): String {
        val r = rpc("set_handle", buildJsonObject { put("p_handle", handle) })
        if (!r.ok) throw CloudError(reason(r))
        return r.body.trim().trim('"')
    }

    /** My room code on the profile, so friends who add me by name can invite me. */
    suspend fun setCode(code: String) {
        val r = rpc("set_code", buildJsonObject { put("p_code", code) })
        if (!r.ok) throw CloudError(reason(r))
    }

    /** A player by name (any case), or null. */
    suspend fun find(handle: String): CloudProfile? {
        val r = authed(HttpMethod.Get, "/rest/v1/profiles", params = listOf("select" to CloudProfile.COLUMNS,
            "handle_lc" to "eq." + handle.trim().lowercase(), "limit" to "1"))
        if (!r.ok) throw CloudError(reason(r))
        return (json.parseToJsonElement(r.body) as? JsonArray)?.firstOrNull()?.let { CloudProfile.of(it.obj()) }
    }

    /** The ranked ladder: the [n] highest ratings of players with at least one rated match. */
    suspend fun ladder(n: Int = 50): List<CloudProfile> {
        val r = authed(HttpMethod.Get, "/rest/v1/profiles", params = listOf("select" to CloudProfile.COLUMNS,
            "ranked_games" to "gt.0", "order" to "elo.desc,ranked_games.desc", "limit" to "$n"))
        if (!r.ok) throw CloudError(reason(r))
        return (json.parseToJsonElement(r.body) as? JsonArray).orEmpty().map { CloudProfile.of(it.obj()) }
    }

    // ------------------------------------------------------------------ saved data

    /** My saved data (settings, history, friends) and when it was last written (server time), or null. */
    suspend fun loadSave(): JsonObject? {
        val uid = session?.uid ?: throw CloudError("signed out")
        val r = authed(HttpMethod.Get, "/rest/v1/saves", params = listOf("select" to "data", "id" to "eq.$uid", "limit" to "1"))
        if (!r.ok) throw CloudError(reason(r))
        val row = (json.parseToJsonElement(r.body) as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        return row["data"] as? JsonObject
    }

    suspend fun putSave(data: JsonObject) {
        val uid = session?.uid ?: throw CloudError("signed out")
        val r = authed(HttpMethod.Post, "/rest/v1/saves", buildJsonObject { put("id", uid); put("data", data) },
            prefer = "resolution=merge-duplicates,return=minimal")
        if (!r.ok) throw CloudError(reason(r))
    }

    // ------------------------------------------------------------------ ranked

    /**
     * Reports a ranked match. The server rates it once both players sent the same result:
     * state "done" (with my new rating and its change), "waiting" (the other report has not come yet),
     * "disputed" (the two reports differ: not rated), "limit" (too many rated games against this player today),
     * "no account" / "bad".
     */
    suspend fun reportRanked(matchId: String, opponent: String, mine: Int, theirs: Int): RankedReply {
        val r = rpc("report_ranked", buildJsonObject {
            put("p_match", matchId); put("p_opp", opponent); put("p_mine", mine); put("p_theirs", theirs)
        })
        if (!r.ok) throw CloudError(reason(r))
        val o = json.parseToJsonElement(r.body).obj()
        return RankedReply(o.str("state"), (o["elo"] as? JsonPrimitive)?.intOrNull, (o["delta"] as? JsonPrimitive)?.intOrNull)
    }

    /** My last rated matches, newest first (as the server has them). */
    suspend fun rankedLog(): List<app.coredump.data.RankedResult> {
        val r = rpc("my_ranked_log")
        if (!r.ok) throw CloudError(reason(r))
        return (json.parseToJsonElement(r.body) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
            app.coredump.data.RankedResult(o.str("opp").ifBlank { "?" }, i("opp_elo"), i("mine"), i("theirs"), i("delta"), i("rating"),
                (o["at_ms"] as? JsonPrimitive)?.longOrNull ?: 0L)
        }
    }

    internal fun note(where: String, t: Throwable) {
        if (t !is CloudError) CrashLog.note("cloud $where", t)
    }
}

class CloudError(msg: String) : Exception(msg)

data class RankedReply(val state: String, val elo: Int?, val delta: Int?)

/** What an e-mail link brought: a session ([type] "signup", "recovery", "magiclink"…) or an [error]. */
data class CloudLink(val session: CloudSession?, val type: String, val error: String?)

/** A signed-in account: tokens (access ends at [expiresAt], epoch ms), user id and e-mail address. */
data class CloudSession(val access: String, val refresh: String, val expiresAt: Long, val uid: String, val email: String) {
    fun encode(): String = buildJsonObject {
        put("a", access); put("r", refresh); put("x", expiresAt); put("u", uid); put("e", email)
    }.toString()

    companion object {
        fun decode(s: String?): CloudSession? {
            val o = Json.parseToJsonElement(s ?: return null).obj()
            val a = o.str("a").ifBlank { return null }
            return CloudSession(a, o.str("r"), (o["x"] as? JsonPrimitive)?.longOrNull ?: 0L, o.str("u"), o.str("e"))
        }
    }
}

/**
 * A player's public profile: [handle] (unique, any case), room [code] (for invites), ranked rating [elo] with its peak,
 * rated games / wins, current and best win streak.
 */
data class CloudProfile(
    val id: String, val handle: String, val code: String, val elo: Int, val peakElo: Int,
    val games: Int, val wins: Int, val streak: Int, val bestStreak: Int,
) {
    companion object {
        const val COLUMNS = "id,handle,code,elo,peak_elo,ranked_games,ranked_wins,streak,best_streak"
        fun of(o: JsonObject): CloudProfile {
            fun i(k: String, d: Int = 0) = (o[k] as? JsonPrimitive)?.intOrNull ?: d
            return CloudProfile(o.str("id"), o.str("handle"), o.str("code"), i("elo", 1000), i("peak_elo", 1000),
                i("ranked_games"), i("ranked_wins"), i("streak"), i("best_streak"))
        }
    }
}

internal fun JsonElement.obj(): JsonObject = this as? JsonObject ?: throw CloudError("unexpected answer")

internal fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()

@Suppress("unused")
internal fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

/** Plain base64 (standard alphabet, padded) for JWT payloads. */
internal object B64 {
    private const val A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    fun decode(s: String): ByteArray {
        val out = ArrayList<Byte>(s.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (c in s) {
            if (c == '=') break
            val v = A.indexOf(c)
            if (v < 0) continue
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out += ((buf shr bits) and 0xFF).toByte()
            }
        }
        return out.toByteArray()
    }
}
