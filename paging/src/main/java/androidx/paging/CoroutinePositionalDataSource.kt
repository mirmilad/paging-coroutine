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

import androidx.annotation.WorkerThread
import androidx.arch.core.util.Function
import androidx.paging.CoroutinePositionalDataSource.LoadInitialCallback
import androidx.paging.PageResult.*

/**
 * Position-based data loader for a fixed-size, countable data set, supporting fixed-size loads at
 * arbitrary page positions.
 *
 *
 * Extend PositionalDataSource if you can load pages of a requested size at arbitrary
 * positions, and provide a fixed item count. If your data source can't support loading arbitrary
 * requested page sizes (e.g. when network page size constraints are only known at runtime), use
 * either [CoroutinePageKeyedDataSource] or [CoroutineItemKeyedDataSource] instead.
 *
 *
 * Note that unless [placeholders are disabled][PagedList.Config.enablePlaceholders]
 * PositionalDataSource requires counting the size of the data set. This allows pages to be tiled in
 * at arbitrary, non-contiguous locations based upon what the user observes in a [PagedList].
 * If placeholders are disabled, initialize with the two parameter
 * [LoadInitialCallback.onResult].
 *
 *
 * Room can generate a Factory of PositionalDataSources for you:
 * <pre>
 * @Dao
 * interface UserDao {
 * @Query("SELECT * FROM user ORDER BY mAge DESC")
 * public abstract DataSource.Factory&lt;Integer, User> loadUsersByAgeDesc();
 * }</pre>
 *
 * @param <T> Type of items being loaded by the PositionalDataSource.
</T> */
abstract class CoroutinePositionalDataSource<T> : CoroutineDataSource<Int, T>() {
    /**
     * Holder object for inputs to [.loadInitial].
     */
    class LoadInitialParams(
        /**
         * Initial load position requested.
         *
         *
         * Note that this may not be within the bounds of your data set, it may need to be adjusted
         * before you execute your load.
         */
        val requestedStartPosition: Int,
        /**
         * Requested number of items to load.
         *
         *
         * Note that this may be larger than available data.
         */
        val requestedLoadSize: Int,
        /**
         * Defines page size acceptable for return values.
         *
         *
         * List of items passed to the callback must be an integer multiple of page size.
         */
        val pageSize: Int,
        /**
         * Defines whether placeholders are enabled, and whether the total count passed to
         * [LoadInitialCallback.onResult] will be ignored.
         */
        val placeholdersEnabled: Boolean
    )

    /**
     * Holder object for inputs to [.loadRange].
     */
    class LoadRangeParams(
        /**
         * Start position of data to load.
         *
         *
         * Returned data must start at this position.
         */
        val startPosition: Int,
        /**
         * Number of items to load.
         *
         *
         * Returned data must be of this size, unless at end of the list.
         */
        val loadSize: Int
    )

    /**
     * Callback for [.loadInitial]
     * to return data, position, and count.
     *
     *
     * A callback should be called only once, and may throw if called again.
     *
     *
     * It is always valid for a DataSource loading method that takes a callback to stash the
     * callback and call it later. This enables DataSources to be fully asynchronous, and to handle
     * temporary, recoverable error states (such as a network error that can be retried).
     *
     * @param <T> Type of items being loaded.
    </T> */
    abstract class LoadInitialCallback<T> {
        /**
         * Called to pass initial load state from a DataSource.
         *
         *
         * Call this method from your DataSource's `loadInitial` function to return data,
         * and inform how many placeholders should be shown before and after. If counting is cheap
         * to compute (for example, if a network load returns the information regardless), it's
         * recommended to pass the total size to the totalCount parameter. If placeholders are not
         * requested (when [placeholdersEnabled] is false), you can instead
         * call [.onResult].
         *
         * @param data List of items loaded from the DataSource. If this is empty, the DataSource
         * is treated as empty, and no further loads will occur.
         * @param position Position of the item at the front of the list. If there are `N`
         * items before the items in data that can be loaded from this DataSource,
         * pass `N`.
         * @param totalCount Total number of items that may be returned from this DataSource.
         * Includes the number in the initial `data` parameter
         * as well as any items that can be loaded in front or behind of
         * `data`.
         */
        internal abstract fun onResult(
            data: List<T>,
            position: Int,
            totalCount: Int
        ) : CoroutinePageResult<T>

        /**
         * Called to pass initial load state from a DataSource without total count,
         * when placeholders aren't requested.
         *
         * **Note:** This method can only be called when placeholders
         * are disabled ([placeholdersEnabled] is false).
         *
         *
         * Call this method from your DataSource's `loadInitial` function to return data,
         * if position is known but total size is not. If placeholders are requested, call the three
         * parameter variant: [.onResult].
         *
         * @param data List of items loaded from the DataSource. If this is empty, the DataSource
         * is treated as empty, and no further loads will occur.
         * @param position Position of the item at the front of the list. If there are `N`
         * items before the items in data that can be provided by this DataSource,
         * pass `N`.
         */
        internal abstract fun onResult(data: List<T>, position: Int) : CoroutinePageResult<T>
    }
    sealed class InitialResult<out T> {
        data class Success<T>(val data: List<T>, val position: Int, val totalCount: Int? = null) : InitialResult<T>()

        data class Error(val throwable: Throwable) : InitialResult<Nothing>()

        object None : InitialResult<Nothing>()
    }

    /**
     * Callback for PositionalDataSource [.loadRange]
     * to return data.
     *
     *
     * A callback should be called only once, and may throw if called again.
     *
     *
     * It is always valid for a DataSource loading method that takes a callback to stash the
     * callback and call it later. This enables DataSources to be fully asynchronous, and to handle
     * temporary, recoverable error states (such as a network error that can be retried).
     *
     * @param <T> Type of items being loaded.
    </T> */
    abstract class LoadRangeCallback<T> {
        /**
         * Called to pass loaded data from [.loadRange].
         *
         * @param data List of items loaded from the DataSource. Must be same size as requested,
         * unless at end of list.
         */
        internal abstract fun onResult(data: List<T>) : CoroutinePageResult<T>
    }
    sealed class LoadRangeResult<out T> {
        data class Success<out T>(val data: List<T>) : LoadRangeResult<T>()

        data class Error(val throwable: Throwable) : LoadRangeResult<Nothing>()

        object None : LoadRangeResult<Nothing>()
    }

    internal class LoadInitialCallbackImpl<T>(
        private val mDataSource: CoroutinePositionalDataSource<*>,
        private val mCountingEnabled: Boolean,
        private val mPageSize: Int
    ) :
        LoadInitialCallback<T>() {


        override fun onResult(
            data: List<T>,
            position: Int,
            totalCount: Int
        ) : CoroutinePageResult<T> {

            if(mDataSource.isInvalid)
                return CoroutinePageResult.Success(INIT, getInvalidResult())

            LoadCallbackHelper.validateInitialLoadParams(data, position, totalCount)

            require(
                !(position + data.size != totalCount
                        && data.size % mPageSize != 0)
            ) {
                ("PositionalDataSource requires initial load"
                        + " size to be a multiple of page size to support internal tiling."
                        + " loadSize " + data.size + ", position " + position
                        + ", totalCount " + totalCount + ", pageSize " + mPageSize)
            }
            return if (mCountingEnabled) {
                val trailingUnloadedCount = totalCount - position - data.size
                CoroutinePageResult.Success(
                    INIT,
                    PageResult(data, position, trailingUnloadedCount, 0)
                )
            } else { // Only occurs when wrapped as contiguous
                CoroutinePageResult.Success(INIT, PageResult(data, position))
            }
        }

        override fun onResult(
            data: List<T>,
            position: Int
        ) : CoroutinePageResult<T> {

            if(mDataSource.isInvalid)
                return CoroutinePageResult.Success(INIT, getInvalidResult())

            require(position >= 0) { "Position must be non-negative" }
            require(!(data.isEmpty() && position != 0)) { "Initial result cannot be empty if items are present in data set." }
            check(!mCountingEnabled) {
                ("Placeholders requested, but totalCount not"
                        + " provided. Please call the three-parameter onResult method, or"
                        + " disable placeholders in the PagedList.Config")
            }
            return CoroutinePageResult.Success(INIT, PageResult(data, position))
        }

        init {
            require(mPageSize >= 1) { "Page size must be non-negative" }
        }
    }

    internal class LoadRangeCallbackImpl<T>(
        private val mDataSource: CoroutinePositionalDataSource<*>,
        @ResultType private val mResultType: Int,
        private val mPositionOffset: Int
    ) :
        LoadRangeCallback<T>() {

        override fun onResult(data: List<T>) : CoroutinePageResult<T> {
            if (mDataSource.isInvalid) {
                return CoroutinePageResult.Success(mResultType, PageResult.getInvalidResult<T>())
            }
            return CoroutinePageResult.Success(mResultType,
                PageResult(
                    data, 0, 0, mPositionOffset
                )
            )

        }
    }

    internal suspend fun dispatchLoadInitial(
        acceptCount: Boolean,
        requestedStartPosition: Int, requestedLoadSize: Int, pageSize: Int
    ) : CoroutinePageResult<T> {
        val callback =
            LoadInitialCallbackImpl<T>(
                this,
                acceptCount,
                pageSize
            )
        val params =
            LoadInitialParams(
                requestedStartPosition, requestedLoadSize, pageSize, acceptCount
            )
        return loadInitial(params).run {
            when(this) {
                InitialResult.None -> CoroutinePageResult.None
                is InitialResult.Error -> CoroutinePageResult.Error(throwable)
                is InitialResult.Success -> {
                    if(totalCount != null)
                        callback.onResult(data, position, totalCount)
                    else
                        callback.onResult(data, position)
                }
            }
        }
    }

    internal suspend fun dispatchLoadRange(
        @ResultType resultType: Int, startPosition: Int,
        count: Int
    ) : CoroutinePageResult<T> {
        val callback: LoadRangeCallback<T> =
            LoadRangeCallbackImpl(
                this, resultType, startPosition
            )
        return if (count == 0) {
            callback.onResult(emptyList())
        } else {
            when(val rangeResult = loadRange(LoadRangeParams(startPosition, count))) {
                LoadRangeResult.None -> CoroutinePageResult.None
                is LoadRangeResult.Error -> CoroutinePageResult.Error(rangeResult.throwable)
                is LoadRangeResult.Success -> callback.onResult(rangeResult.data)
            }
        }
    }

    /**
     * Load initial list data.
     *
     *
     * This method is called to load the initial page(s) from the DataSource.
     *
     *
     * Result list must be a multiple of pageSize to enable efficient tiling.
     *
     * @param params Parameters for initial load, including requested start position, load size, and
     * page size.
     */
    @WorkerThread
    abstract suspend fun loadInitial(
        params: LoadInitialParams
    ) : InitialResult<T>

    /**
     * Called to load a range of data from the DataSource.
     *
     *
     * This method is called to load additional pages from the DataSource after the
     * LoadInitialCallback passed to dispatchLoadInitial has initialized a PagedList.
     *
     *
     * Unlike [.loadInitial], this method must return
     * the number of items requested, at the position requested.
     *
     * @param params Parameters for load, including start position and load size.
     */
    @WorkerThread
    abstract suspend fun loadRange(
        params: LoadRangeParams
    ) : LoadRangeResult<T>

    public override fun isContiguous(): Boolean {
        return false
    }

    fun wrapAsContiguousWithoutPlaceholders(): CoroutineContiguousDataSource<Int, T> {
        return ContiguousWithoutPlaceholdersWrapper(this)
    }

    class ContiguousWithoutPlaceholdersWrapper<Value>(
        val mSource: CoroutinePositionalDataSource<Value>
    ) : CoroutineContiguousDataSource<Int, Value>() {
        override fun addInvalidatedCallback(
            onInvalidatedCallback: InvalidatedCallback
        ) {
            mSource.addInvalidatedCallback(onInvalidatedCallback)
        }

        override fun removeInvalidatedCallback(
            onInvalidatedCallback: InvalidatedCallback
        ) {
            mSource.removeInvalidatedCallback(onInvalidatedCallback)
        }

        override fun invalidate() {
            mSource.invalidate()
        }

        override fun isInvalid(): Boolean {
            return mSource.isInvalid
        }

        override fun <ToValue> mapByPage(
            function: Function<List<Value>, List<ToValue>>
        ): DataSource<Int, ToValue> {
            throw UnsupportedOperationException(
                "Inaccessible inner type doesn't support map op"
            )
        }

        override fun <ToValue> map(
            function: Function<Value, ToValue>
        ): DataSource<Int, ToValue> {
            throw UnsupportedOperationException(
                "Inaccessible inner type doesn't support map op"
            )
        }

        override suspend fun dispatchLoadInitial(
            key: Int?, initialLoadSize: Int, pageSize: Int,
            enablePlaceholders: Boolean
        ) : CoroutinePageResult<Value> {
            val convertPosition = key ?: 0
            // Note enablePlaceholders will be false here, but we don't have a way to communicate
// this to PositionalDataSource. This is fine, because only the list and its position
// offset will be consumed by the LoadInitialCallback.
            return mSource.dispatchLoadInitial(
                false, convertPosition, initialLoadSize,
                pageSize)
        }

        override suspend fun dispatchLoadAfter(
            currentEndIndex: Int, currentEndItem: Value, pageSize: Int
        ) : CoroutinePageResult<Value> {
            val startIndex = currentEndIndex + 1
            return mSource.dispatchLoadRange(
                PageResult.APPEND, startIndex, pageSize
            )
        }

        override suspend fun dispatchLoadBefore(
            currentBeginIndex: Int, currentBeginItem: Value,
            pageSize: Int
        ) : CoroutinePageResult<Value> {
            var startIndex = currentBeginIndex - 1
            return if (startIndex < 0) { // trigger empty list load
                mSource.dispatchLoadRange(
                    PageResult.PREPEND, startIndex, 0
                )
            } else {
                val loadSize = Math.min(pageSize, startIndex + 1)
                startIndex = startIndex - loadSize + 1
                mSource.dispatchLoadRange(
                    PageResult.PREPEND, startIndex, loadSize
                )
            }
        }

        override fun getKey(position: Int, item: Value?): Int {
            return position
        }

    }

    override fun <V> mapByPage(
        function: Function<List<T>, List<V>>
    ): CoroutinePositionalDataSource<V> {
        return CoroutineWrapperPositionalDataSource(this, function)
    }

    override fun <V> map(function: Function<T, V>): CoroutinePositionalDataSource<V> {
        return mapByPage(createListFunction(function))
    }

    companion object {
        /**
         * Helper for computing an initial position in
         * [.loadInitial] when total data set size can be
         * computed ahead of loading.
         *
         *
         * The value computed by this function will do bounds checking, page alignment, and positioning
         * based on initial load size requested.
         *
         *
         * Example usage in a PositionalDataSource subclass:
         * <pre>
         * class ItemDataSource extends PositionalDataSource&lt;Item> {
         * private int computeCount() {
         * // actual count code here
         * }
         *
         * private List&lt;Item> loadRangeInternal(int startPosition, int loadCount) {
         * // actual load code here
         * }
         *
         * @Override
         * public void loadInitial(@NonNull LoadInitialParams params,
         * @NonNull LoadInitialCallback&lt;Item> callback) {
         * int totalCount = computeCount();
         * int position = computeInitialLoadPosition(params, totalCount);
         * int loadSize = computeInitialLoadSize(params, position, totalCount);
         * callback.onResult(loadRangeInternal(position, loadSize), position, totalCount);
         * }
         *
         * @Override
         * public void loadRange(@NonNull LoadRangeParams params,
         * @NonNull LoadRangeCallback&lt;Item> callback) {
         * callback.onResult(loadRangeInternal(params.startPosition, params.loadSize));
         * }
         * }</pre>
         *
         * @param params Params passed to [.loadInitial],
         * including page size, and requested start/loadSize.
         * @param totalCount Total size of the data set.
         * @return Position to start loading at.
         *
         * @see .computeInitialLoadSize
         */
        fun computeInitialLoadPosition(
            params: LoadInitialParams,
            totalCount: Int
        ): Int {
            val position = params.requestedStartPosition
            val initialLoadSize = params.requestedLoadSize
            val pageSize = params.pageSize
            var pageStart = position / pageSize * pageSize
            // maximum start pos is that which will encompass end of list
            val maximumLoadPage =
                (totalCount - initialLoadSize + pageSize - 1) / pageSize * pageSize
            pageStart = Math.min(maximumLoadPage, pageStart)
            // minimum start position is 0
            pageStart = Math.max(0, pageStart)
            return pageStart
        }

        /**
         * Helper for computing an initial load size in
         * [.loadInitial] when total data set size can be
         * computed ahead of loading.
         *
         *
         * This function takes the requested load size, and bounds checks it against the value returned
         * by [.computeInitialLoadPosition].
         *
         *
         * Example usage in a PositionalDataSource subclass:
         * <pre>
         * class ItemDataSource extends PositionalDataSource&lt;Item> {
         * private int computeCount() {
         * // actual count code here
         * }
         *
         * private List&lt;Item> loadRangeInternal(int startPosition, int loadCount) {
         * // actual load code here
         * }
         *
         * @Override
         * public void loadInitial(@NonNull LoadInitialParams params,
         * @NonNull LoadInitialCallback&lt;Item> callback) {
         * int totalCount = computeCount();
         * int position = computeInitialLoadPosition(params, totalCount);
         * int loadSize = computeInitialLoadSize(params, position, totalCount);
         * callback.onResult(loadRangeInternal(position, loadSize), position, totalCount);
         * }
         *
         * @Override
         * public void loadRange(@NonNull LoadRangeParams params,
         * @NonNull LoadRangeCallback&lt;Item> callback) {
         * callback.onResult(loadRangeInternal(params.startPosition, params.loadSize));
         * }
         * }</pre>
         *
         * @param params Params passed to [.loadInitial],
         * including page size, and requested start/loadSize.
         * @param initialLoadPosition Value returned by
         * [.computeInitialLoadPosition]
         * @param totalCount Total size of the data set.
         * @return Number of items to load.
         *
         * @see .computeInitialLoadPosition
         */
        fun computeInitialLoadSize(
            params: LoadInitialParams,
            initialLoadPosition: Int, totalCount: Int
        ): Int {
            return Math.min(totalCount - initialLoadPosition, params.requestedLoadSize)
        }
    }
}