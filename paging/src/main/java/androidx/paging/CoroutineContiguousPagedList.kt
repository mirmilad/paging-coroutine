package androidx.paging

import android.annotation.SuppressLint
import androidx.annotation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor


internal class CoroutineContiguousPagedList<K, V>(
    val mDataSource: CoroutineContiguousDataSource<K, V>/* synthetic access */,
    mainThreadExecutor: Executor,
    boundaryCallback: BoundaryCallback<V>?,
    config: Config,
    key: K?,
    lastLoad: Int
) : CoroutinePagedList<V>(
    PagedStorage<V>(),
    mainThreadExecutor,
    boundaryCallback,
    config
), PagedStorage.Callback {

    @FetchState
    var mPrependWorkerState = READY_TO_FETCH/* synthetic access */
    @FetchState
    var mAppendWorkerState = READY_TO_FETCH/* synthetic access */

    var mPrependItemsRequested = 0/* synthetic access */
    var mAppendItemsRequested = 0/* synthetic access */

    var mReplacePagesWithNulls = false/* synthetic access */

    val mShouldTrim: Boolean =
        mDataSource.supportsPageDropping() && mConfig.maxSize != PagedList.Config.MAX_SIZE_UNBOUNDED/* synthetic access */


    @AnyThread
    suspend fun onPageResult(
        @PageResult.ResultType resultType: Int,
        pageResult: PageResult<V>
    ) = withContext(Dispatchers.Main) {

        if (pageResult.isInvalid) {
            detach()
            return@withContext
        }

        if (isDetached()) {
            // No op, have detached
            return@withContext
        }

        val page = pageResult.page
        if (resultType == PageResult.INIT) {
            mStorage.init(
                pageResult.leadingNulls, page, pageResult.trailingNulls,
                pageResult.positionOffset, this@CoroutineContiguousPagedList
            )
            if (mLastLoad == LAST_LOAD_UNSPECIFIED) {
                // Because the CoroutineContiguousPagedList wasn't initialized with a last load position,
                // initialize it to the middle of the initial load
                mLastLoad = pageResult.leadingNulls + pageResult.positionOffset + page.size / 2
            }
        } else {
            // if we end up trimming, we trim from side that's furthest from most recent access
            val trimFromFront = mLastLoad > mStorage.getMiddleOfLoadedRange()

            // is the new page big enough to warrant pre-trimming (i.e. dropping) it?
            val skipNewPage = mShouldTrim && mStorage.shouldPreTrimNewPage(
                mConfig.maxSize, mRequiredRemainder, page.size
            )

            if (resultType == PageResult.APPEND) {
                if (skipNewPage && !trimFromFront) {
                    // don't append this data, drop it
                    mAppendItemsRequested = 0
                    mAppendWorkerState = READY_TO_FETCH
                } else {
                    mStorage.appendPage(page, this@CoroutineContiguousPagedList)
                }
            } else if (resultType == PageResult.PREPEND) {
                if (skipNewPage && trimFromFront) {
                    // don't append this data, drop it
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
                        ) {
                            // trimmed from front, ensure we can fetch in that dir
                            mPrependWorkerState = READY_TO_FETCH
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
                            mAppendWorkerState = READY_TO_FETCH
                        }
                    }
                }
            }
        }

        if (mBoundaryCallback != null) {
            val deferEmpty = mStorage.size == 0
            val deferBegin = (!deferEmpty
                    && resultType == PageResult.PREPEND
                    && pageResult.page.size == 0)
            val deferEnd = (!deferEmpty
                    && resultType == PageResult.APPEND
                    && pageResult.page.size == 0)
            deferBoundaryCallbacks(deferEmpty, deferBegin, deferEnd)
        }
    }


    @kotlin.annotation.Retention
    @IntDef(READY_TO_FETCH, FETCHING, DONE_FETCHING)
    internal annotation class FetchState

    init {
        mLastLoad = lastLoad

        if (mDataSource.isInvalid) {
            detach()
        } else {
//            mDataSource.dispatchLoadInitial(
//                key,
//                mConfig.initialLoadSizeHint,
//                mConfig.pageSize,
//                mConfig.enablePlaceholders,
//                mMainThreadExecutor,
//                mReceiver
//            )
            mDataSource.coroutineScope.launch {
               mDataSource.dispatchLoadInitial(
                   key,
                   mConfig.initialLoadSizeHint,
                   mConfig.pageSize,
                   mConfig.enablePlaceholders
               ).run {
                   when(this) {
                       is CoroutineDataSource.CoroutinePageResult.Success<V> -> onPageResult(type, pageResult)
                   }

               }
            }
        }
        //mShouldTrim =  mDataSource.supportsPageDropping() && mConfig.maxSize != PagedList.Config.MAX_SIZE_UNBOUNDED
    }

    @MainThread
    internal override fun dispatchUpdatesSinceSnapshot(
        pagedListSnapshot: PagedList<V>, callback: PagedList.Callback
    ) {
        val snapshot = pagedListSnapshot.mStorage

        val newlyAppended = mStorage.getNumberAppended() - snapshot.getNumberAppended()
        val newlyPrepended = mStorage.getNumberPrepended() - snapshot.getNumberPrepended()

        val previousTrailing = snapshot.getTrailingNullCount()
        val previousLeading = snapshot.getLeadingNullCount()

        // Validate that the snapshot looks like a previous version of this list - if it's not,
        // we can't be sure we'll dispatch callbacks safely
        require(
            !(snapshot.isEmpty()
                    || newlyAppended < 0
                    || newlyPrepended < 0
                    || mStorage.getTrailingNullCount() != Math.max(
                previousTrailing - newlyAppended,
                0
            )
                    || mStorage.getLeadingNullCount() != Math.max(
                previousLeading - newlyPrepended,
                0
            )
                    || mStorage.getStorageCount() != snapshot.getStorageCount() + newlyAppended + newlyPrepended)
        ) { "Invalid snapshot provided - doesn't appear" + " to be a snapshot of this PagedList" }

        if (newlyAppended != 0) {
            val changedCount = Math.min(previousTrailing, newlyAppended)
            val addedCount = newlyAppended - changedCount

            val endPosition = snapshot.getLeadingNullCount() + snapshot.getStorageCount()
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
    protected override fun loadAroundInternal(index: Int) {
        val prependItems = getPrependItemsRequested(
            mConfig.prefetchDistance, index,
            mStorage.getLeadingNullCount()
        )
        val appendItems = getAppendItemsRequested(
            mConfig.prefetchDistance, index,
            mStorage.getLeadingNullCount() + mStorage.getStorageCount()
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

        val position = mStorage.getLeadingNullCount() + mStorage.getPositionOffset()

        // safe to access first item here - mStorage can't be empty if we're prepending
        val item = mStorage.getFirstLoadedItem()
//        mBackgroundThreadExecutor.execute(Runnable {
//            if (isDetached()) {
//                return@Runnable
//            }
//            if (mDataSource.isInvalid) {
//                detach()
//            } else {
//                mDataSource.dispatchLoadBefore(
//                    position, item, mConfig.pageSize,
//                    mMainThreadExecutor, mReceiver
//                )
//            }
//        })
        mDataSource.coroutineScope.launch {
            if (isDetached()) {
                return@launch
            }
            if (mDataSource.isInvalid) {
                detach()
            } else {
                mDataSource.dispatchLoadBefore(
                        position, item, mConfig.pageSize
                ).run {
                    when(this) {
                        is CoroutineDataSource.CoroutinePageResult.Success -> onPageResult(type, pageResult)
                    }
                }
            }
        }
    }

    @MainThread
    private fun scheduleAppend() {
        if (mAppendWorkerState != READY_TO_FETCH) {
            return
        }
        mAppendWorkerState = FETCHING

        val position =
            mStorage.getLeadingNullCount() + mStorage.getStorageCount() - 1 + mStorage.getPositionOffset()

        // safe to access first item here - mStorage can't be empty if we're appending
        val item = mStorage.getLastLoadedItem()
//        mBackgroundThreadExecutor.execute(Runnable {
//            if (isDetached()) {
//                return@Runnable
//            }
//            if (mDataSource.isInvalid) {
//                detach()
//            } else {
//                mDataSource.dispatchLoadAfter(
//                    position, item, mConfig.pageSize,
//                    mMainThreadExecutor, mReceiver
//                )
//            }
//        })
        mDataSource.coroutineScope.launch {
            if (isDetached()) {
                return@launch
            }
            if (mDataSource.isInvalid) {
                detach()
            } else {
                mDataSource.dispatchLoadAfter(
                    position, item, mConfig.pageSize
                ).run {
                    when(this) {
                        is CoroutineDataSource.CoroutinePageResult.Success -> onPageResult(type, pageResult)
                    }
                }
            }
        }
    }

    internal override fun isContiguous(): Boolean {
        return true
    }

    override fun getDataSource(): CoroutineDataSource<*, V> {
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
            mStorage.getLeadingNullCount() > 0 || mStorage.getTrailingNullCount() > 0
    }

    @SuppressLint("RestrictedApi")
    @MainThread
    override fun onPagePrepended(leadingNulls: Int, changedCount: Int, addedCount: Int) {
        // consider whether to post more work, now that a page is fully prepended
        mPrependItemsRequested = mPrependItemsRequested - changedCount - addedCount
        mPrependWorkerState = READY_TO_FETCH
        if (mPrependItemsRequested > 0) {
            // not done prepending, keep going
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
    override fun onPageAppended(endPosition: Int, changedCount: Int, addedCount: Int) {
        // consider whether to post more work, now that a page is fully appended
        mAppendItemsRequested = mAppendItemsRequested - changedCount - addedCount
        mAppendWorkerState = READY_TO_FETCH
        if (mAppendItemsRequested > 0) {
            // not done appending, keep going
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
        throw IllegalStateException("Tiled callback on CoroutineContiguousPagedList")
    }

    @MainThread
    override fun onPageInserted(start: Int, count: Int) {
        throw IllegalStateException("Tiled callback on CoroutineContiguousPagedList")
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
}