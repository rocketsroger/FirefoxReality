/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.vrbrowser.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import mozilla.components.concept.sync.AccountObserver
import mozilla.components.concept.sync.AuthType
import mozilla.components.concept.sync.OAuthAccount
import mozilla.components.concept.sync.Profile
import mozilla.components.service.fxa.SyncEngine
import mozilla.components.service.fxa.manager.SyncEnginesStorage
import mozilla.components.service.fxa.sync.SyncReason
import mozilla.components.service.fxa.sync.SyncStatusObserver
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.vrbrowser.VRBrowserApplication
import org.mozilla.vrbrowser.utils.SystemUtils
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

class Accounts constructor(val context: Context) {

    private val LOGTAG = SystemUtils.createLogtag(Accounts::class.java)

    enum class AccountStatus {
        SIGNED_IN,
        SIGNED_OUT,
        NEEDS_RECONNECT
    }

    enum class LoginOrigin {
        BOOKMARKS,
        HISTORY,
        SETTINGS,
        UNDEFINED
    }

    var loginOrigin: LoginOrigin = LoginOrigin.UNDEFINED
    var accountStatus = AccountStatus.SIGNED_OUT
    private val accountListeners = ArrayList<AccountObserver>()
    private val syncListeners = ArrayList<SyncStatusObserver>()
    private val services = (context.applicationContext as VRBrowserApplication).services
    private val syncStorage = SyncEnginesStorage(context)
    var lastSync = 0L
    var isSyncing = false

    private val syncStatusObserver = object : SyncStatusObserver {
        override fun onStarted() {
            isSyncing = true
            syncListeners.toMutableList().forEach {
                Handler(Looper.getMainLooper()).post {
                    it.onStarted()
                }
            }
        }

        override fun onIdle() {
            isSyncing = false
            lastSync = System.currentTimeMillis()
            syncListeners.toMutableList().forEach {
                Handler(Looper.getMainLooper()).post {
                    it.onIdle()
                }
            }
        }

        override fun onError(error: Exception?) {
            isSyncing = false
            syncListeners.toMutableList().forEach {
                Handler(Looper.getMainLooper()).post {
                    it.onError(error)
                }
            }
        }
    }

    private val accountObserver = object : AccountObserver {
        override fun onAuthenticated(account: OAuthAccount, authType: AuthType) {
            accountStatus = AccountStatus.SIGNED_IN

            // Enable syncing after signing in
            syncStorage.setStatus(SyncEngine.Bookmarks, SettingsStore.getInstance(context).isBookmarksSyncEnabled)
            syncStorage.setStatus(SyncEngine.History, SettingsStore.getInstance(context).isHistorySyncEnabled)
            services.accountManager.syncNowAsync(SyncReason.EngineChange, false)

            account.deviceConstellation().refreshDevicesAsync()
            accountListeners.toMutableList().forEach {
                Handler(Looper.getMainLooper()).post {
                    it.onAuthenticated(account, authType)
                }
            }
        }

        override fun onAuthenticationProblems() {
            accountStatus = AccountStatus.NEEDS_RECONNECT
            accountListeners.toMutableList().forEach {
                Handler(Looper.getMainLooper()).post {
                    it.onAuthenticationProblems()
                }
            }
        }

        override fun onLoggedOut() {
            accountStatus = AccountStatus.SIGNED_OUT
            accountListeners.toMutableList().forEach {
                Handler(Looper.getMainLooper()).post {
                    it.onLoggedOut()
                }
            }
        }

        override fun onProfileUpdated(profile: Profile) {
            accountListeners.toMutableList().forEach {
                Handler(Looper.getMainLooper()).post {
                    it.onProfileUpdated(profile)
                }
            }
        }
    }

    init {
        services.accountManager.registerForSyncEvents(
                syncStatusObserver, ProcessLifecycleOwner.get(), false
        )
        services.accountManager.register(accountObserver)
        accountStatus = if (services.accountManager.authenticatedAccount() != null) {
            if (services.accountManager.accountNeedsReauth()) {
                AccountStatus.NEEDS_RECONNECT

            } else {
                AccountStatus.SIGNED_IN
            }

        } else {
            AccountStatus.SIGNED_OUT
        }
    }


    fun addAccountListener(aListener: AccountObserver) {
        if (!accountListeners.contains(aListener)) {
            accountListeners.add(aListener)
        }
    }

    fun removeAccountListener(aListener: AccountObserver) {
        accountListeners.remove(aListener)
    }

    fun removeAllAccountListeners() {
        accountListeners.clear()
    }

    fun addSyncListener(aListener: SyncStatusObserver) {
        if (!syncListeners.contains(aListener)) {
            syncListeners.add(aListener)
        }
    }

    fun removeSyncListener(aListener: SyncStatusObserver) {
        syncListeners.remove(aListener)
    }

    fun removeAllSyncListeners() {
        syncListeners.clear()
    }

    fun authUrlAsync(): CompletableFuture<String?>? {
        return CoroutineScope(Dispatchers.Main).future {
            services.accountManager.beginAuthenticationAsync().await()
        }
    }

    fun refreshDevicesAsync(): CompletableFuture<Boolean?>? {
        return CoroutineScope(Dispatchers.Main).future {
            services.accountManager.authenticatedAccount()?.deviceConstellation()?.refreshDevicesAsync()?.await()
        }
    }

    fun updateProfileAsync(): CompletableFuture<Unit?>? {
        return CoroutineScope(Dispatchers.Main).future {
            services.accountManager.updateProfileAsync().await()
        }
    }

    fun syncNowAsync(reason: SyncReason = SyncReason.Startup,
                     debounce: Boolean = false): CompletableFuture<Unit?>?{
        return CoroutineScope(Dispatchers.Main).future {
            services.accountManager.syncNowAsync(reason, debounce).await()
        }
    }

    fun setSyncStatus(engine: SyncEngine, value: Boolean) {

        when(engine) {
            SyncEngine.Bookmarks -> SettingsStore.getInstance(context).isBookmarksSyncEnabled = value
            SyncEngine.History -> SettingsStore.getInstance(context).isHistorySyncEnabled = value
        }

        syncStorage.setStatus(engine, value)
    }

    fun accountProfile(): Profile? {
        return services.accountManager.accountProfile()
    }

    fun logoutAsync(): CompletableFuture<Unit?>? {
        return CoroutineScope(Dispatchers.Main).future {
            services.accountManager.logoutAsync().await()
        }
    }

    fun getAuthenticationUrlAsync(): CompletableFuture<String> {
        val future: CompletableFuture<String> = CompletableFuture()

        // If we're already logged-in, and not in a "need to reconnect" state, logout.
        if (services.accountManager.authenticatedAccount() != null && !services.accountManager.accountNeedsReauth()) {
            services.accountManager.logoutAsync()
            future.complete(null)
        }

        // Otherwise, obtain an authentication URL and load it in the gecko session.
        // Recovering from "need to reconnect" state is treated the same as just logging in.
        val futureUrl = authUrlAsync()
        if (futureUrl == null) {
            Logger(LOGTAG).debug("Got a 'null' futureUrl")
            services.accountManager.logoutAsync()
            future.complete(null)
        }

        Executors.newSingleThreadExecutor().submit {
            try {
                val url = futureUrl!!.get()
                if (url == null) {
                    Logger(LOGTAG).debug("Got a 'null' url after resolving futureUrl")
                    services.accountManager.logoutAsync()
                    future.complete(null)
                }
                Logger(LOGTAG).debug("Got an auth url: " + url!!)

                // Actually process the url on the main thread.
                Handler(Looper.getMainLooper()).post {
                    Logger(LOGTAG).debug("We got an authentication url, we can continue...")
                    future.complete(url)
                }

            } catch (e: ExecutionException) {
                Logger(LOGTAG).debug("Error obtaining auth url", e)
                future.complete(null)

            } catch (e: InterruptedException) {
                Logger(LOGTAG).debug("Error obtaining auth url", e)
                future.complete(null)
            }
        }

        return future
    }

    fun isEngineEnabled(engine: SyncEngine): Boolean {
        return syncStorage.getStatus()[engine]?: false
    }

    fun isSignedIn(): Boolean {
        return (accountStatus == AccountStatus.SIGNED_IN)
    }

}