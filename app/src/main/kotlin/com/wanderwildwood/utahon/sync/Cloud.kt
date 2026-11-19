package com.wanderwildwood.utahon.sync

import android.content.Context
import com.wanderwildwood.utahon.R
import com.wanderwildwood.utahon.data.Setlist
import com.wanderwildwood.utahon.data.Setlists
import com.wanderwildwood.utahon.data.Song
import com.wanderwildwood.utahon.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The songs kept in step with a Nextcloud, when the reader has signed in to one; nothing at all
 * happens here until then, and the app works the same without it.
 *
 * A sync runs when the app is opened, a few seconds after a song is saved, set or deleted (so a
 * run of changes goes as one), and when the reader asks for one. One runs at a time.
 *
 * The account is kept in the app's private preferences, sealed (see [Secrets]). The password in
 * it is an app password made for this phone, which the server can revoke on its own, without
 * the real one changing.
 *
 * Or the account is the phone's, the one Home holds ([HomeAccount]). Then the app keeps only the
 * choice ([HOME_WHO]: server and user) and the stamp it last took; the sealed account is a copy
 * of Home's, written from Home on start, resume and each change there, and removed when Home
 * signs out, is gone, or holds another account ([followHome]).
 */
class Cloud private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val store = Store.get(context)
    private val setlists = Setlists.get(context)
    private val prefs = context.getSharedPreferences("nextcloud", Context.MODE_PRIVATE)
    private val linksFile = File(context.filesDir, "sync/links.json")
    private val phoneSuffix = context.getString(R.string.phone_copy_suffix)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var pending: Job? = null
    private var lastTry = 0L

    private fun loadAccount(): Account? {
        val read = readStoredAccount(prefs.getString(ACCOUNT, null), Secrets::seal, Secrets::open)
        read.migrated?.let { prefs.edit().putString(ACCOUNT, it).apply() }
        return read.account
    }

    enum class State { OFF, IDLE, SYNCING, UNREACHABLE, REFUSED, FAILED }

    private val _account = MutableStateFlow(loadAccount())
    val account: StateFlow<Account?> = _account

    private val _folder = MutableStateFlow(prefs.getString(FOLDER, null) ?: DEFAULT_FOLDER)
    val folder: StateFlow<String> = _folder

    private val _state = MutableStateFlow(if (_account.value == null) State.OFF else State.IDLE)
    val state: StateFlow<State> = _state

    private val _last = MutableStateFlow(prefs.getLong(LAST, 0L))
    /** When the last sync finished, in milliseconds; 0 for never. */
    val lastSync: StateFlow<Long> = _last

    /**
     * A sign-in going on away from the screen that started it, so that Back, or the screen built
     * again, does not lose it: [browser] while the server's page in the browser is waited on,
     * and [problem] (a string) what ended the last one, until it has been said.
     */
    data class SignIn(val browser: Boolean = false, val problem: Int? = null)

    /**
     * The account Home holds that this app follows ([HomeAccount.Held.who]), or null when the
     * app uses its own sign-in or none. Set while Home is signed out too: [account] is then null.
     */
    private val _homeWho = MutableStateFlow(prefs.getString(HOME_WHO, null))
    val homeWho: StateFlow<String?> = _homeWho

    private val _signing = MutableStateFlow(SignIn())
    val signing: StateFlow<SignIn> = _signing
    private var signingJob: Job? = null

    init {
        store.onChanged = { syncSoon() }
        setlists.onChanged = { syncSoon() }
        resumeBrowser()
    }

    /**
     * On opening the app: Home asked after its account (when it is the one used), and a sync,
     * unless one was tried in the last minute.
     */
    fun onOpen() {
        scope.launch {
            val took = followHomeNow() is HomeAccount.Step.Take
            if (took || System.currentTimeMillis() - lastTry > 60_000) syncNow()
        }
    }

    /** On resuming, and on each change notice from Home: Home asked again, off the main thread. */
    fun followHome() {
        scope.launch { if (followHomeNow() is HomeAccount.Step.Take) syncNow() }
    }

    /**
     * Where the account is Home's: asks Home and does as [HomeAccount.follow] says. A new password
     * for the same account takes the old one's place, with the folder and what was synced kept.
     * When Home has signed out or is gone, the copy is let go and this reads as signed out, still
     * following, so the same account is taken again when Home has it back; when Home holds
     * another account, the choice goes too. The songs here, the folder and what was synced stay.
     * Never on the main thread.
     */
    @Synchronized
    private fun followHomeNow(): HomeAccount.Step {
        val who = _homeWho.value ?: return HomeAccount.Step.Keep
        val step = HomeAccount.follow(who, prefs.getString(HOME_STAMP, null).orEmpty(), HomeAccount.ask(appContext))
        when (step) {
            is HomeAccount.Step.Keep -> Unit
            is HomeAccount.Step.Take -> {
                val a = Account(step.held.server, step.held.user, step.held.password)
                val sealed = Secrets.seal(a.toJson().toString())
                prefs.edit().apply { if (sealed != null) putString(ACCOUNT, sealed) else remove(ACCOUNT) }
                    .putString(HOME_STAMP, step.held.stamp).apply()
                _account.value = a
                _state.value = State.IDLE
            }
            is HomeAccount.Step.Drop -> {
                pending?.cancel()
                _account.value = null
                _state.value = State.OFF
                prefs.edit().remove(ACCOUNT).putString(HOME_STAMP, "")
                    .apply { if (!step.following) remove(HOME_WHO) }.apply()
                if (!step.following) _homeWho.value = null
            }
        }
        return step
    }

    fun syncNow() {
        if (_account.value == null) return
        pending?.cancel()
        scope.launch { sync() }
    }

    /**
     * A sync now, as the list asks for one when it is pulled down from its top, returning once it
     * is done with the state it left. Waits for one already running, then runs its own.
     */
    suspend fun syncAndWait(): State {
        if (_account.value == null) return State.OFF
        pending?.cancel()
        withContext(Dispatchers.IO) { sync() }
        return _state.value
    }

    private fun syncSoon() {
        if (_account.value == null) return
        pending?.cancel()
        pending = scope.launch {
            delay(5_000)
            sync()
        }
    }

    private suspend fun sync() = lock.withLock {
        // An account taken from Home is never used past a change there.
        followHomeNow()
        val a = _account.value ?: return@withLock
        val folder = _folder.value
        lastTry = System.currentTimeMillis()
        _state.value = State.SYNCING
        val remote = NextcloudFolder(a, folder)
        val sync = Sync(remote, StoreSide(store), loadLinks(), phoneSuffix)
        // Setlists here that could not be read are put together with the cloud's as if never
        // synced, so the few made since are not sent over the rest.
        val damaged = setlists.damaged
        var lists = if (damaged) null else listsLink()
        val outcome = try {
            val failed = sync.run()
            // The songs first, so a setlist that comes down finds its songs here; a song the
            // server would not take holds the setlists up no more than the other songs.
            lists = SetlistSync.run(remote, ListsSide(setlists), lists, phoneSuffix)
            if (failed != null) State.FAILED else State.IDLE
        } catch (_: Refused) {
            State.REFUSED
        } catch (_: Unreachable) {
            State.UNREACHABLE
        } catch (_: IOException) {
            State.FAILED
        } catch (_: RuntimeException) {
            // A server answer this could not read. Said as a failed sync rather than a crash.
            State.FAILED
        }
        // Signed out, or moved to another folder, while it ran: what it found is not kept.
        if (_account.value != a || _folder.value != folder) return@withLock
        saveLinks(sync.links.values)
        saveListsLink(lists)
        if (damaged && lists != null) setlists.mended()
        if (outcome == State.IDLE) {
            _last.value = System.currentTimeMillis()
            prefs.edit().putLong(LAST, _last.value).apply()
        }
        _state.value = outcome
    }

    /**
     * Keeps [a] after making sure it opens the files on the server, and starts the first sync.
     * Throws [Refused] or [Unreachable] when the account will not do.
     */
    suspend fun signIn(a: Account) = keep(a, null)

    /**
     * The phone's Nextcloud account, the one Home holds, taken at the reader's press in place of
     * the app's own sign-in, after making sure it opens the files on the server. Followed from
     * now on ([followHome]). The same account as the one signed in to here keeps its folder and
     * what was synced. Runs on the app's own scope, so Back on the screen does not stop it
     * halfway. Throws [Refused] or [Unreachable] when the account will not do.
     */
    suspend fun useHome(held: HomeAccount.Held) {
        stopBrowser()
        scope.async {
            keep(Account(held.server, held.user, held.password), held)
        }.await()
    }

    /**
     * [a] kept, and with it, in the same step, whether it is Home's ([held]) or the app's own
     * (null), so the password is never kept without saying whose it is. A new account starts
     * with the default folder and nothing synced; Home's account, when it is the one already
     * signed in to here, keeps its folder and what was synced. Then a sync.
     */
    private suspend fun keep(a: Account, held: HomeAccount.Held?) {
        fun sameAsHere(): Boolean {
            val current = _account.value ?: return false
            return held != null && HomeAccount.who(current.server, current.user) == held.who
        }
        withContext(Dispatchers.IO) { NextcloudFolder(a, if (sameAsHere()) _folder.value else DEFAULT_FOLDER).probe() }
        lock.withLock {
            val same = sameAsHere()
            // Only the sealed form is ever written; with no keystore the account lasts this run only.
            val sealed = Secrets.seal(a.toJson().toString())
            prefs.edit().apply {
                if (sealed != null) putString(ACCOUNT, sealed) else remove(ACCOUNT)
                if (!same) {
                    putString(FOLDER, DEFAULT_FOLDER).remove(LAST).remove(LISTS_ETAG).remove(LISTS_STAMP)
                }
                if (held != null) putString(HOME_WHO, held.who).putString(HOME_STAMP, held.stamp)
                else remove(HOME_WHO).remove(HOME_STAMP)
            }.apply()
            if (!same) {
                linksFile.delete()
                _folder.value = DEFAULT_FOLDER
                _last.value = 0L
            }
            _homeWho.value = held?.who
            _account.value = a
            _state.value = State.IDLE
        }
        syncNow()
    }

    /** A wait for the browser's sign-in given up, as another way in was taken. */
    private fun stopBrowser() {
        signingJob?.cancel()
        prefs.edit().remove(LOGIN).apply()
        _signing.value = SignIn()
    }

    /** [a] kept as [signIn] does, in the background; what went wrong is said through [signing]. */
    fun signInLater(a: Account) {
        signingJob?.cancel()
        _signing.value = SignIn()
        signingJob = scope.launch { _signing.value = SignIn(problem = problemSigningIn(a)) }
    }

    /**
     * The server's page is open in the browser: the server is asked every two seconds whether
     * the reader has finished there, for the twenty minutes it gives. What it started is kept
     * (sealed), so a process let go while the browser is in front takes it up again.
     */
    fun awaitBrowser(started: LoginFlow.Started, asked: String) {
        if (_signing.value.browser) return
        val since = System.currentTimeMillis()
        val kept = JSONObject().put("login", started.loginUrl).put("poll", started.pollUrl)
            .put("token", started.token).put("asked", asked).put("since", since)
        Secrets.seal(kept.toString())?.let { prefs.edit().putString(LOGIN, it).apply() }
        startPolling(started, asked, since)
    }

    /** The problem in [signing] has been said. */
    fun problemSaid() {
        _signing.value = _signing.value.copy(problem = null)
    }

    private fun resumeBrowser() {
        val stored = prefs.getString(LOGIN, null) ?: return
        val o = Secrets.open(stored)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val since = o?.optLong("since") ?: 0L
        val asked = o?.optString("asked").orEmpty()
        val started = o?.let {
            runCatching { LoginFlow.Started(it.getString("login"), it.getString("poll"), it.getString("token")) }.getOrNull()
        }
        if (started == null || asked.isBlank() || _account.value != null || System.currentTimeMillis() - since !in 0L until LOGIN_WINDOW) {
            prefs.edit().remove(LOGIN).apply()
            return
        }
        startPolling(started, asked, since)
    }

    private fun startPolling(started: LoginFlow.Started, asked: String, since: Long) {
        signingJob?.cancel()
        _signing.value = SignIn(browser = true)
        signingJob = scope.launch {
            var problem: Int? = null
            try {
                problem = pollBrowser(started, asked, since)
            } finally {
                prefs.edit().remove(LOGIN).apply()
                _signing.value = SignIn(problem = problem)
            }
        }
    }

    /** The browser's sign-in waited on; null when it went through or ran out of time, else the problem. */
    private suspend fun pollBrowser(started: LoginFlow.Started, asked: String, since: Long): Int? {
        // An answer that keeps failing is said, not waited on in silence.
        var failed = 0
        while (System.currentTimeMillis() - since < LOGIN_WINDOW) {
            delay(2000)
            val got = try {
                // An answer, even "not yet", ends a run of failures.
                LoginFlow.poll(started).also { failed = 0 }
            } catch (_: Refused) {
                return R.string.cloud_refused
            } catch (_: Exception) {
                if (++failed >= 15) return R.string.cloud_browser_lost
                null
            }
            // The address typed here, not the one the server calls itself by, which behind a
            // proxy can be a name this phone cannot reach.
            if (got != null) return problemSigningIn(got.copy(server = LoginFlow.normalise(asked)))
        }
        return null
    }

    private suspend fun problemSigningIn(a: Account): Int? = try {
        signIn(a)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Refused) {
        R.string.cloud_refused
    } catch (_: Exception) {
        R.string.cloud_unreachable
    }

    /**
     * Forgets the account and what was synced; the songs here stay as they are. For Home's
     * account this is "Stop using it": the choice, the stamp and the copy go, and the app's own
     * sign-in comes back, empty.
     */
    fun signOut() {
        pending?.cancel()
        _account.value = null
        _state.value = State.OFF
        _last.value = 0L
        prefs.edit().remove(ACCOUNT).remove(LAST).remove(FOLDER).remove(LISTS_ETAG).remove(LISTS_STAMP)
            .remove(HOME_WHO).remove(HOME_STAMP).apply()
        _homeWho.value = null
        _folder.value = DEFAULT_FOLDER
        scope.launch { lock.withLock { linksFile.delete() } }
    }

    /**
     * Another folder in the same Nextcloud. Nothing synced with the old one carries over: the
     * songs here are matched by name with the ones in the new folder, and the rest sent.
     */
    fun setFolder(typed: String) {
        val clean = typed.trim().trim('/').ifEmpty { DEFAULT_FOLDER }
        if (clean == _folder.value) return
        scope.launch {
            lock.withLock {
                linksFile.delete()
                prefs.edit().putString(FOLDER, clean).remove(LAST).remove(LISTS_ETAG).remove(LISTS_STAMP).apply()
                _folder.value = clean
                _last.value = 0L
            }
            sync()
        }
    }

    private fun listsLink(): ListsLink? {
        val etag = prefs.getString(LISTS_ETAG, null) ?: return null
        return ListsLink(etag, prefs.getString(LISTS_STAMP, "").orEmpty())
    }

    private fun saveListsLink(link: ListsLink?) {
        prefs.edit().apply {
            if (link == null) remove(LISTS_ETAG).remove(LISTS_STAMP) else putString(LISTS_ETAG, link.etag).putString(LISTS_STAMP, link.stamp)
        }.apply()
    }

    private fun loadLinks(): Map<String, Link> =
        runCatching { Link.listFromJson(linksFile.readText()) }.getOrDefault(emptyMap())

    private fun saveLinks(links: Collection<Link>) {
        linksFile.parentFile?.mkdirs()
        val tmp = File(linksFile.parentFile, "links.json.tmp")
        tmp.writeText(Link.listToJson(links))
        if (!tmp.renameTo(linksFile)) {
            linksFile.delete()
            tmp.renameTo(linksFile)
        }
    }

    /** The store as a sync sees it. */
    private class StoreSide(private val store: Store) : Local {
        override fun songs(): List<Song> = store.all.value
        override fun unreadable(): Set<String> = store.unreadable
        override fun listed(): Boolean = store.listed
        override fun put(song: Song, expect: String?) = store.putSynced(song, expect)
        override fun remove(id: String, expect: String) = store.removeSynced(id, expect)
        override fun newId() = store.newId()
        override fun now() = store.now()
    }

    /** The setlists as a sync sees them. */
    private class ListsSide(private val setlists: Setlists) : LocalLists {
        override fun lists(): List<Setlist> = setlists.all.value
        override fun stamp() = setlists.stamp
        override fun put(lists: List<Setlist>, expect: String) = setlists.putSynced(lists, expect)
        override fun newId() = java.util.UUID.randomUUID().toString()
    }

    companion object {
        const val DEFAULT_FOLDER = "Songbook"
        private const val LISTS_ETAG = "lists_etag"
        private const val LISTS_STAMP = "lists_stamp"
        private const val ACCOUNT = "account"
        private const val FOLDER = "folder"
        private const val LAST = "last_sync"
        private const val LOGIN = "browser_login"
        private const val HOME_WHO = "home_who"
        private const val HOME_STAMP = "home_stamp"

        /** As long as Nextcloud keeps a sign-in in the browser open. */
        private const val LOGIN_WINDOW = 20 * 60 * 1000L

        @Volatile
        private var cloud: Cloud? = null

        fun get(context: Context): Cloud =
            cloud ?: synchronized(this) { cloud ?: Cloud(context.applicationContext).also { cloud = it } }
    }
}
