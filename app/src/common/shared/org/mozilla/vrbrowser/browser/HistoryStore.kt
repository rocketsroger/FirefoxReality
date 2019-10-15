/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.vrbrowser.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.future
import mozilla.components.concept.storage.PageObservation
import mozilla.components.concept.storage.VisitInfo
import mozilla.components.concept.storage.VisitType
import mozilla.components.service.fxa.sync.SyncStatusObserver
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.vrbrowser.VRBrowserApplication
import org.mozilla.vrbrowser.utils.SystemUtils
import java.util.concurrent.CompletableFuture

class HistoryStore constructor(val context: Context) {

    private val LOGTAG = SystemUtils.createLogtag(HistoryStore::class.java)

    private var listeners = ArrayList<HistoryListener>()
    private val storage = (context.applicationContext as VRBrowserApplication).places.history

    // Bookmarks might have changed during sync, so notify our listeners.
    private val syncStatusObserver = object : SyncStatusObserver {
        override fun onStarted() {}

        override fun onIdle() {
            Logger(LOGTAG).debug("Detected that sync is finished, notifying listeners")
            notifyListeners()
        }

        override fun onError(error: Exception?) {}
    }

    init {
        (context.applicationContext as VRBrowserApplication).services.accountManager.registerForSyncEvents(
                syncStatusObserver, ProcessLifecycleOwner.get(), false
        )
    }

    interface HistoryListener {
        fun onHistoryUpdated()
    }

    fun addListener(aListener: HistoryListener) {
        if (!listeners.contains(aListener)) {
            listeners.add(aListener)
        }
    }

    fun removeListener(aListener: HistoryListener) {
        listeners.remove(aListener)
    }

    fun removeAllListeners() {
        listeners.clear()
    }

    fun getHistory(): CompletableFuture<List<String>?> = GlobalScope.future {
        storage.getVisited()
    }

    fun getDetailedHistory(): CompletableFuture<List<VisitInfo>?> = GlobalScope.future {
        storage.getDetailedVisits(0, excludeTypes = listOf(
                VisitType.NOT_A_VISIT,
                VisitType.REDIRECT_TEMPORARY,
                VisitType.REDIRECT_PERMANENT))
    }

    fun recordVisit(aURL: String, visitType: VisitType) = GlobalScope.future {
        storage.recordVisit(aURL, visitType)
        notifyListeners()
    }

    fun recordObservation(aURL: String, observation: PageObservation) = GlobalScope.future {
        storage.recordObservation(aURL, observation)
        notifyListeners()
    }

    fun deleteHistory(aUrl: String, timestamp: Long) = GlobalScope.future {
        storage.deleteVisit(aUrl, timestamp)
        notifyListeners()
    }

    fun deleteVisitsFor(aUrl: String) = GlobalScope.future {
        storage.deleteVisitsFor(aUrl)
        notifyListeners()
    }

    fun deleteEverything() = GlobalScope.future {
        storage.deleteEverything()
        notifyListeners()
    }

    fun deleteVisitsSince(since: Long) = GlobalScope.future {
        storage.deleteVisitsSince(since)
        notifyListeners()
    }

    fun deleteVisitsBetween(startTime: Long, endTime: Long) = GlobalScope.future {
        storage.deleteVisitsBetween(startTime, endTime)
        notifyListeners()
    }

    fun getVisited(uris: List<String>) = GlobalScope.future {
        storage.getVisited(uris)
    }

    fun isInHistory(aURL: String): CompletableFuture<Boolean> = GlobalScope.future {
        var result = storage.getVisited(listOf(aURL))
        result.isNotEmpty() && result[0]
    }

    private fun notifyListeners() {
        if (listeners.size > 0) {
            val listenersCopy = ArrayList(listeners)
            Handler(Looper.getMainLooper()).post {
                for (listener in listenersCopy) {
                    listener.onHistoryUpdated()
                }
            }
        }
    }
}

