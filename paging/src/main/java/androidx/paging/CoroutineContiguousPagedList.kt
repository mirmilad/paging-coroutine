/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.paging

import androidx.annotation.AnyThread
import androidx.annotation.IntDef
import androidx.annotation.MainThread
import androidx.paging.PageResult.ResultType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.util.concurrent.Executor

internal class CoroutineContiguousPagedList<K, V>(
    /* synthetic access */val mDataSource: CoroutineContiguousDataSource<K, V>,
                          mainThreadExecutor: Executor,
                          backgroundThreadExecutor: Executor,
                          boundaryCallback: BoundaryCallback<V>?,
                          config: Config,
                          key: K?,
                          lastLoad: Int
) : PagedList<V>(
    PagedStorage<V>(), mainThreadExecutor, backgroundThreadExecutor,
    boundaryCallback, config
), PagedStorage.Callback {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
        READY_TO_FETCH,
        FETCHING,
        DONE_FETCHING
    )
    internal annotation class FetchState

    @FetchState
    var mPrependWorkerState =
        READY_TO_FETCH
    @FetchState
    var mAppendWorkerState = READY_TO_FETCH
    var mPrependItemsRequested = 0
    var mAppendItemsRequested = 0
    var mReplacePagesWithNulls = false
    /* synthetic access */ val mShouldTrim: Boolean = (mDataSource.supportsPageDropping()
            && mConfig.maxSize != Config.MAX_SIZE_UNBOUNDED)
    var mReceiver: /* synthetic access */PageResult.Receiver<V> =
        object : PageResult.Receiver<V>() {
            // Creation thread for initial synchronous load, otherwise main thread
// Safe to access main thread only state - no other thread has reference during construction
            @AnyThread
            override fun onPageResult(
                @ResultType resultType: Int,
                pageResult: PageResult<V>
            ) {
                if (pageResult.isInvalid) {
                    detach()
                    return
                }
                if (isDetached) { // No op, have detached
                    return
                }
                val page = pageResult.page
                if (resultType == PageResult.INIT) {
                    mStorage.init(
                        pageResult.leadingNulls, page, pageResult.trailingNulls,
                        pageResult.positionOffset, this@CoroutineContiguousPagedList
                    )
                    if (mLastLoad == LAST_LOAD_UNSPECIFIED) { // Because the ContiguousPagedList wasn't initialized with a last load position,
// initialize it to the middle of the initial load
                        mLastLoad =
                            pageResult.leadingNulls + pageResult.positionOffset + page.size / 2
                    }
                } else { // if we end up trimming, we trim from side that's furthest from most recent access
                    val trimFromFront =
                        mLastLoad > mStorage.middleOfLoadedRange
                    // is the new page big enough to warrant pre-trimming (i.e. dropping) it?
                    val skipNewPage = (mShouldTrim
                            && mStorage.shouldPreTrimNewPage(
                        mConfig.maxSize, mRequiredRemainder, page.size
                    ))
                    if (resultType == PageResult.APPEND) {
                        if (skipNewPage && !trimFromFront) { // don't append this data, drop it
                            mAppendItemsRequested = 0
                            mAppendWorkerState = READY_TO_FETCH
                        } else {
                            mStorage.appendPage(page, this@CoroutineContiguousPagedList)
                        }
                    } else if (resultType == PageResult.PREPEND) {
                        if (skipNewPage && trimFromFront) { // don't append this data, drop it
                            mPrependItemsRequested = 0
                            mPrependWorkerState = READY_TO_FETCH
                        } else {
                            mStorage.prependPage(page, this@CoroutineContiguousPagedList)
                        }
                    } else {
                        throw IllegalArgumentException("unexpected resultType $resultType")
                    }
                    if (mShouldTrim) {
                        if (trimFromFront) {
                            if (mPrependWorkerState != FETCHING) {
                                if (mStorage.trimFromFront(
                                        mReplacePagesWithNulls,
                                        mConfig.maxSize,
                                        mRequiredRemainder,
                                        this@CoroutineContiguousPagedList
                                    )
                                ) { // trimmed from front, ensure we can fetch in that dir
                                    mPrependWorkerState =
                                        READY_TO_FETCH
                                }
                            }
                        } else {
                            if (mAppendWorkerState != FETCHING) {
                                if (mStorage.trimFromEnd(
                                        mReplacePagesWithNulls,
                                        mConfig.maxSize,
                                        mRequiredRemainder,
                                        this@CoroutineContiguousPagedList
                                    )
                                ) {
                                    mAppendWorkerState =
                                        READY_TO_FETCH
                                }
                            }
                        }
                    }
                }
                if (mBoundaryCallback != null) {
                    val deferEmpty = mStorage.size == 0
                    val deferBegin = (!deferEmpty
                            && resultType == PageResult.PREPEND && pageResult.page.size == 0)
                    val deferEnd = (!deferEmpty
                            && resultType == PageResult.APPEND && pageResult.page.size == 0)
                    deferBoundaryCallbacks(deferEmpty, deferBegin, deferEnd)
                }
            }
        }

    @MainThread
    public override fun dispatchUpdatesSinceSnapshot(
        pagedListSnapshot: PagedList<V>, callback: Callback
    ) {
        val snapshot = pagedListSnapshot.mStorage
        val newlyAppended = mStorage.numberAppended - snapshot.numberAppended
        val newlyPrepended = mStorage.numberPrepended - snapshot.numberPrepended
        val previousTrailing = snapshot.trailingNullCount
        val previousLeading = snapshot.leadingNullCount
        // Validate that the snapshot looks like a previous version of this list - if it's not,
// we can't be sure we'll dispatch callbacks safely
        require(
            !(snapshot.isEmpty()
                    || newlyAppended < 0 || newlyPrepended < 0 || mStorage.trailingNullCount != Math.max(
                previousTrailing - newlyAppended,
                0
            ) || mStorage.leadingNullCount != Math.max(
                previousLeading - newlyPrepended,
                0
            ) || (mStorage.storageCount
                    != snapshot.storageCount + newlyAppended + newlyPrepended))
        ) {
            ("Invalid snapshot provided - doesn't appear"
                    + " to be a snapshot of this PagedList")
        }
        if (newlyAppended != 0) {
            val changedCount = Math.min(previousTrailing, newlyAppended)
            val addedCount = newlyAppended - changedCount
            val endPosition = snapshot.leadingNullCount + snapshot.storageCount
            if (changedCount != 0) {
                callback.onChanged(endPosition, changedCount)
            }
            if (addedCount != 0) {
                callback.onInserted(endPosition + changedCount, addedCount)
            }
        }
        if (newlyPrepended != 0) {
            val changedCount = Math.min(previousLeading, newlyPrepended)
            val addedCount = newlyPrepended - changedCount
            if (changedCount != 0) {
                callback.onChanged(previousLeading, changedCount)
            }
            if (addedCount != 0) {
                callback.onInserted(0, addedCount)
            }
        }
    }

    @MainThread
    override fun loadAroundInternal(index: Int) {
        val prependItems = getPrependItemsRequested(
            mConfig.prefetchDistance, index,
            mStorage.leadingNullCount
        )
        val appendItems = getAppendItemsRequested(
            mConfig.prefetchDistance, index,
            mStorage.leadingNullCount + mStorage.storageCount
        )
        mPrependItemsRequested = Math.max(prependItems, mPrependItemsRequested)
        if (mPrependItemsRequested > 0) {
            schedulePrepend()
        }
        mAppendItemsRequested = Math.max(appendItems, mAppendItemsRequested)
        if (mAppendItemsRequested > 0) {
            scheduleAppend()
        }
    }

    @MainThread
    private fun schedulePrepend() {
        if (mPrependWorkerState != READY_TO_FETCH) {
            return
        }
        mPrependWorkerState = FETCHING
        val position = mStorage.leadingNullCount + mStorage.positionOffset
        // safe to access first item here - mStorage can't be empty if we're prepending
        val item = mStorage.firstLoadedItem
        mBackgroundThreadExecutor.execute(Runnable {
            if (isDetached) {
                return@Runnable
            }
            if (mDataSource.isInvalid) {
                detach()
            } else {
                mDataSource.dispatchLoadBefore(
                    position, item, mConfig.pageSize,
                    mMainThreadExecutor, mReceiver
                )
            }
        })
    }

    @MainThread
    private fun scheduleAppend() {
        if (mAppendWorkerState != READY_TO_FETCH) {
            return
        }
        mAppendWorkerState = FETCHING
        val position = mStorage.leadingNullCount + mStorage.storageCount - 1 + mStorage.positionOffset
        // safe to access first item here - mStorage can't be empty if we're appending
        val item = mStorage.lastLoadedItem
        mBackgroundThreadExecutor.execute(Runnable {
            if (isDetached) {
                return@Runnable
            }
            if (mDataSource.isInvalid) {
                detach()
            } else {
                mDataSource.dispatchLoadAfter(
                    position, item, mConfig.pageSize,
                    mMainThreadExecutor, mReceiver
                )
            }
        })
    }

    public override fun isContiguous(): Boolean {
        return true
    }

    override fun getDataSource(): DataSource<*, V> {
        return mDataSource
    }

    override fun getLastKey(): Any? {
        return mDataSource.getKey(mLastLoad, mLastItem)
    }

    @MainThread
    override fun onInitialized(count: Int) {
        notifyInserted(0, count)
        // simple heuristic to decide if, when dropping pages, we should replace with placeholders
        mReplacePagesWithNulls =
            mStorage.leadingNullCount > 0 || mStorage.trailingNullCount > 0
    }

    @MainThread
    override fun onPagePrepended(
        leadingNulls: Int,
        changedCount: Int,
        addedCount: Int
    ) { // consider whether to post more work, now that a page is fully prepended
        mPrependItemsRequested = mPrependItemsRequested - changedCount - addedCount
        mPrependWorkerState = READY_TO_FETCH
        if (mPrependItemsRequested > 0) { // not done prepending, keep going
            schedulePrepend()
        }
        // finally dispatch callbacks, after prepend may have already been scheduled
        notifyChanged(leadingNulls, changedCount)
        notifyInserted(0, addedCount)
        offsetAccessIndices(addedCount)
    }

    @MainThread
    override fun onEmptyPrepend() {
        mPrependWorkerState = DONE_FETCHING
    }

    @MainThread
    override fun onPageAppended(
        endPosition: Int,
        changedCount: Int,
        addedCount: Int
    ) { // consider whether to post more work, now that a page is fully appended
        mAppendItemsRequested = mAppendItemsRequested - changedCount - addedCount
        mAppendWorkerState = READY_TO_FETCH
        if (mAppendItemsRequested > 0) { // not done appending, keep going
            scheduleAppend()
        }
        // finally dispatch callbacks, after append may have already been scheduled
        notifyChanged(endPosition, changedCount)
        notifyInserted(endPosition + changedCount, addedCount)
    }

    @MainThread
    override fun onEmptyAppend() {
        mAppendWorkerState = DONE_FETCHING
    }

    @MainThread
    override fun onPagePlaceholderInserted(pageIndex: Int) {
        throw IllegalStateException("Tiled callback on ContiguousPagedList")
    }

    @MainThread
    override fun onPageInserted(start: Int, count: Int) {
        throw IllegalStateException("Tiled callback on ContiguousPagedList")
    }

    override fun onPagesRemoved(startOfDrops: Int, count: Int) {
        notifyRemoved(startOfDrops, count)
    }

    override fun onPagesSwappedToPlaceholder(startOfDrops: Int, count: Int) {
        notifyChanged(startOfDrops, count)
    }

    companion object {
        private const val READY_TO_FETCH = 0
        private const val FETCHING = 1
        private const val DONE_FETCHING = 2
        const val LAST_LOAD_UNSPECIFIED = -1
        fun getPrependItemsRequested(prefetchDistance: Int, index: Int, leadingNulls: Int): Int {
            return prefetchDistance - (index - leadingNulls)
        }

        fun getAppendItemsRequested(
            prefetchDistance: Int, index: Int, itemsBeforeTrailingNulls: Int
        ): Int {
            return index + prefetchDistance + 1 - itemsBeforeTrailingNulls
        }
    }

    init {
        mLastLoad = lastLoad
        if (mDataSource.isInvalid) {
            detach()
        } else {
            mDataSource.dispatchLoadInitial(
                key,
                mConfig.initialLoadSizeHint,
                mConfig.pageSize,
                mConfig.enablePlaceholders,
                mMainThreadExecutor,
                mReceiver
            )
        }
        //mShouldTrim = (mDataSource.supportsPageDropping()
        //        && mConfig.maxSize != Config.MAX_SIZE_UNBOUNDED)
    }
}