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
import androidx.annotation.WorkerThread
import androidx.paging.PageResult.ResultType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

class CoroutineTiledPagedList<T> @WorkerThread constructor(
    /* synthetic access */val mDataSource: CoroutinePositionalDataSource<T>,
                          mainThreadExecutor: Executor,
                          boundaryCallback: BoundaryCallback<T>?,
                          config: Config,
                          position: Int
) :
    CoroutinePagedList<T>(
        PagedStorage<T>(), mainThreadExecutor,
        boundaryCallback, config
    ), PagedStorage.Callback {
    //var mReceiver: /* synthetic access */PageResult.Receiver<T> =
    //    object : PageResult.Receiver<T>() {
            // Creation thread for initial synchronous load, otherwise main thread
// Safe to access main thread only state - no other thread has reference during construction
            @AnyThread
    //        override fun onPageResult(
            internal suspend fun onPageResult(
                @ResultType type: Int,
                pageResult: PageResult<T>
            ) = withContext(Dispatchers.Main) {
                if (pageResult.isInvalid) {
                    detach()
                    return@withContext
                }
                if (isDetached) { // No op, have detached
                    return@withContext
                }
                require(!(type != PageResult.INIT && type != PageResult.TILE)) { "unexpected resultType$type" }
                val page = pageResult.page
                if (mStorage.pageCount == 0) {
                    mStorage.initAndSplit(
                        pageResult.leadingNulls, page, pageResult.trailingNulls,
                        pageResult.positionOffset, mConfig.pageSize, this@CoroutineTiledPagedList
                    )
                } else {
                    mStorage.tryInsertPageAndTrim(
                        pageResult.positionOffset,
                        page,
                        mLastLoad,
                        mConfig.maxSize,
                        mRequiredRemainder,
                        this@CoroutineTiledPagedList
                    )
                }
                if (mBoundaryCallback != null) {
                    val deferEmpty = mStorage.size == 0
                    val deferBegin = (!deferEmpty
                            && pageResult.leadingNulls == 0 && pageResult.positionOffset == 0)
                    val size = size
                    val deferEnd = (!deferEmpty
                            && (type == PageResult.INIT && pageResult.trailingNulls == 0
                            || (type == PageResult.TILE
                            && pageResult.positionOffset + mConfig.pageSize >= size)))
                    deferBoundaryCallbacks(deferEmpty, deferBegin, deferEnd)
                }
            }
        //}

    public override fun isContiguous(): Boolean {
        return false
    }

    override fun getDataSource(): DataSource<*, T> {
        return mDataSource
    }

    override fun getLastKey(): Any? {
        return mLastLoad
    }

    override fun dispatchUpdatesSinceSnapshot(
        pagedListSnapshot: PagedList<T>,
        callback: Callback
    ) {
        val snapshot = pagedListSnapshot.mStorage
        require(
            !(snapshot.isEmpty()
                    || mStorage.size != snapshot.size)
        ) {
            ("Invalid snapshot provided - doesn't appear"
                    + " to be a snapshot of this PagedList")
        }
        // loop through each page and signal the callback for any pages that are present now,
// but not in the snapshot.
        val pageSize = mConfig.pageSize
        val leadingNullPages = mStorage.leadingNullCount / pageSize
        val pageCount = mStorage.pageCount
        var i = 0
        while (i < pageCount) {
            val pageIndex = i + leadingNullPages
            var updatedPages = 0
            // count number of consecutive pages that were added since the snapshot...
            while (updatedPages < mStorage.pageCount && mStorage.hasPage(
                    pageSize,
                    pageIndex + updatedPages
                )
                && !snapshot.hasPage(pageSize, pageIndex + updatedPages)
            ) {
                updatedPages++
            }
            // and signal them all at once to the callback
            if (updatedPages > 0) {
                callback.onChanged(pageIndex * pageSize, pageSize * updatedPages)
                i += updatedPages - 1
            }
            i++
        }
    }

    override fun loadAroundInternal(index: Int) {
        mStorage.allocatePlaceholders(index, mConfig.prefetchDistance, mConfig.pageSize, this)
    }

    override fun onInitialized(count: Int) {
        notifyInserted(0, count)
    }

    override fun onPagePrepended(leadingNulls: Int, changed: Int, added: Int) {
        throw IllegalStateException("Contiguous callback on TiledPagedList")
    }

    override fun onPageAppended(endPosition: Int, changed: Int, added: Int) {
        throw IllegalStateException("Contiguous callback on TiledPagedList")
    }

    override fun onEmptyPrepend() {
        throw IllegalStateException("Contiguous callback on TiledPagedList")
    }

    override fun onEmptyAppend() {
        throw IllegalStateException("Contiguous callback on TiledPagedList")
    }

    override fun onPagePlaceholderInserted(pageIndex: Int) { // placeholder means initialize a load
        //mBackgroundThreadExecutor.execute(Runnable {
        mDataSource.coroutineScope.launch(Dispatchers.Default) {
            if (isDetached) {
                //return@Runnable
                return@launch
            }
            val pageSize = mConfig.pageSize
            if (mDataSource.isInvalid) {
                detach()
            } else {
                val startPosition = pageIndex * pageSize
                val count = Math.min(pageSize, mStorage.size - startPosition)
                mDataSource.dispatchLoadRange(
                    PageResult.TILE, startPosition, count
                ).run { onPageResult(type, pageResult) }
            }
            //})
        }
    }

    override fun onPageInserted(start: Int, count: Int) {
        notifyChanged(start, count)
    }

    override fun onPagesRemoved(startOfDrops: Int, count: Int) {
        notifyRemoved(startOfDrops, count)
    }

    override fun onPagesSwappedToPlaceholder(startOfDrops: Int, count: Int) {
        notifyChanged(startOfDrops, count)
    }

    init {
        val pageSize = mConfig.pageSize
        mLastLoad = position
        if (mDataSource.isInvalid) {
            detach()
        } else {
            mDataSource.coroutineScope.launch {
                val firstLoadSize =
                    Math.max(mConfig.initialLoadSizeHint / pageSize, 2) * pageSize
                val idealStart = position - firstLoadSize / 2
                val roundedPageStart = Math.max(0, idealStart / pageSize * pageSize)
                mDataSource.dispatchLoadInitial(
                    true, roundedPageStart, firstLoadSize,
                    pageSize
                ).run { onPageResult(type, pageResult) }
            }
        }
    }
}