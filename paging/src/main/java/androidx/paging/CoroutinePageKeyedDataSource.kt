package androidx.paging

import androidx.annotation.GuardedBy
import androidx.arch.core.util.Function

/**
 * Incremental data loader for page-keyed content, where requests return keys for next/previous
 * pages.
 *
 *
 * Implement a DataSource using CoroutinePageKeyedDataSource if you need to use data from page `N - 1`
 * to load page `N`. This is common, for example, in network APIs that include a next/previous
 * link or key with each page load.
 *
 *
 * The `InMemoryByPageRepository` in the
 * [PagingWithNetworkSample](https://github.com/googlesamples/android-architecture-components/blob/master/PagingWithNetworkSample/README.md)
 * shows how to implement a network CoroutinePageKeyedDataSource using
 * [Retrofit](https://square.github.io/retrofit/), while
 * handling swipe-to-refresh, network errors, and retry.
 *
 * @param <Key> Type of data used to query Value types out of the DataSource.
 * @param <Value> Type of items being loaded by the DataSource.
</Value></Key> */
abstract class CoroutinePageKeyedDataSource<Key, Value> : CoroutineContiguousDataSource<Key, Value>() {

    private val mKeyLock = Any()

    @GuardedBy("mKeyLock")
    private var mNextKey: Key? = null

    @GuardedBy("mKeyLock")
    private var mPreviousKey: Key? = null

    private/* synthetic access */ var previousKey: Key?
        get() = synchronized(mKeyLock) {
            return mPreviousKey
        }
        set(previousKey) = synchronized(mKeyLock) {
            mPreviousKey = previousKey
        }

    private/* synthetic access */ var nextKey: Key?
        get() = synchronized(mKeyLock) {
            return mNextKey
        }
        set(nextKey) = synchronized(mKeyLock) {
            mNextKey = nextKey
        }

    internal /* synthetic access */ fun initKeys(previousKey: Key?, nextKey: Key?) {
        synchronized(mKeyLock) {
            mPreviousKey = previousKey
            mNextKey = nextKey
        }
    }

    /**
     * Holder object for inputs to [.loadInitial].
     *
     * @param <Key> Type of data used to query pages.
    </Key> */
    data class LoadInitialParams<Key>(
        /**
         * Requested number of items to load.
         *
         *
         * Note that this may be larger than available data.
         */
        val requestedLoadSize: Int,
        /**
         * Defines whether placeholders are enabled, and whether the total count passed to
         * [LoadInitialCallback.onResult] will be ignored.
         */
        val placeholdersEnabled: Boolean
    )

    /**
     * Holder object for inputs to [.loadBefore] and
     * [.loadAfter].
     *
     * @param <Key> Type of data used to query pages.
    </Key> */
    data class LoadParams<Key>(
        /**
         * Load items before/after this key.
         *
         *
         * Returned data must begin directly adjacent to this position.
         */
        val key: Key,
        /**
         * Requested number of items to load.
         *
         *
         * Returned page can be of this size, but it may be altered if that is easier, e.g. a
         * network data source where the backend defines page size.
         */
        val requestedLoadSize: Int
    )


    /**
     * Return Type for [.loadInitial]
     * to return data and, optionally, position/count information.
     *
     *
     * If you can compute the number of items in the data set before and after the loaded range,
     * get an instance with the five parameter constructor to return that information.
     * You can skip passing this information by using the three parameter
     * constructor, either if it's difficult to compute, or if
     * [LoadInitialParams.placeholdersEnabled] is `false`, so the positioning
     * information will be ignored.
     *
     *
     * @param <Key> Type of data used to query pages.
     * @param <Value> Type of items being loaded.
    </Value></Key> */
    /**
     * Used to return initial load state from a DataSource.
     *
     *
     * Get an instance of this type in your DataSource's `loadInitial` function to return data,
     * and inform how many placeholders should be shown before and after. If counting is cheap
     * to compute (for example, if a network load returns the information regardless).
     *
     *
     * It is always valid to pass a different amount of data than what is requested. Return an
     * empty list if there is no more data to load.
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
    data class InitialResult<Key, Value>(val data: List<Value>,
                             val position: Int,
                             val totalCount: Int,
                             val previousPageKey: Key?,
                             val nextPageKey: Key?) {

        constructor(data: List<Value>, previousPageKey: Key?, nextPageKey: Key?) : this(data, 0, 0, previousPageKey, nextPageKey)
    }



    /**
     * Return Type for CoroutinePageKeyedDataSource [.loadBefore] and
     * [.loadAfter] to return data.
     *
     *
     * @param <Key> Type of data used to query pages.
     * @param <Value> Type of items being loaded.
    </Value></Key> */
    data class LoadResult<Key, Value>(val data: List<Value>, val adjacentPageKey: Key?)

    internal class InitialResultHandler<Key, Value>(
        private val mDataSource: CoroutinePageKeyedDataSource<Key, Value>,
        private val mCountingEnabled: Boolean
    ) {

        fun getPageResult(initalResult: InitialResult<Key, Value>) : CoroutinePageResult<Value> {
            val pageResult = initalResult.run {
                if (totalCount == 0 && position == 0)
                    getPageResult(data, previousPageKey, nextPageKey)
                else
                    getPageResult(data, position, totalCount, previousPageKey, nextPageKey)

            }
            return CoroutinePageResult(PageResult.INIT, pageResult)
        }

        private fun getPageResult(
            data: List<Value>, position: Int, totalCount: Int,
            previousPageKey: Key?, nextPageKey: Key?
        ) : PageResult<Value> {

            if (mDataSource.isInvalid) {
                return PageResult.getInvalidResult<Value>()
            }

            LoadCallbackHelper.validateInitialLoadParams(data, position, totalCount)

            // setup keys before dispatching data, so guaranteed to be ready
            mDataSource.initKeys(previousPageKey, nextPageKey)

            val trailingUnloadedCount = totalCount - position - data.size
                if (mCountingEnabled) {
                    return PageResult(
                            data, position, trailingUnloadedCount, 0
                        )
                } else {
                    return PageResult(
                            data,
                            position
                        )
                }
            }

        private fun getPageResult(
            data: List<Value>, previousPageKey: Key?,
            nextPageKey: Key?
        ) : PageResult<Value> {

            if (mDataSource.isInvalid) {
                return PageResult.getInvalidResult<Value>()
            }

            mDataSource.initKeys(previousPageKey, nextPageKey)
            return PageResult(data, 0, 0, 0)
        }
    }

    internal class LoadResultHandler<Key, Value>(
        private val mDataSource: CoroutinePageKeyedDataSource<Key, Value>,
        private val type: Int
    ) {
        
         fun getPageResult(loadResult: LoadResult<Key, Value>) : CoroutinePageResult<Value> {
             if (mDataSource.isInvalid) {
                 return CoroutinePageResult(type, PageResult.getInvalidResult<Value>())
             }

             if (type == PageResult.APPEND) {
                 mDataSource.nextKey = loadResult.adjacentPageKey
             } else {
                 mDataSource.previousKey = loadResult.adjacentPageKey
             }
             
             return CoroutinePageResult(type, PageResult(loadResult.data, 0, 0, 0))
        }
    }

    override fun getKey(position: Int, item: Value?): Key? {
        // don't attempt to persist keys, since we currently don't pass them to initial load
        return null
    }

    override fun supportsPageDropping(): Boolean {
        /* To support page dropping when PageKeyed, we'll need to:
         *    - Stash keys for every page we have loaded (can id by index relative to loadInitial)
         *    - Drop keys for any page not adjacent to loaded content
         *    - And either:
         *        - Allow impl to signal previous page key: onResult(data, nextPageKey, prevPageKey)
         *        - Re-trigger loadInitial, and break assumption it will only occur once.
         */
        return false
    }


    internal override suspend fun dispatchLoadInitial(
        key: Key?, initialLoadSize: Int, pageSize: Int,
        enablePlaceholders: Boolean
    ) : CoroutinePageResult<Value> {

        val initialResult = loadInitial(LoadInitialParams(initialLoadSize, enablePlaceholders))
        return InitialResultHandler(this, enablePlaceholders).getPageResult(initialResult)
    }


    internal override suspend fun dispatchLoadAfter(
        currentEndIndex: Int, currentEndItem: Value,
        pageSize: Int
    ) : CoroutinePageResult<Value> {
        val key = nextKey
        if (key != null) {
            val afterResult = loadAfter(LoadParams(key, pageSize))
            return LoadResultHandler(this, PageResult.APPEND).getPageResult(afterResult)
        } else {
            return CoroutinePageResult(PageResult.APPEND, PageResult.getEmptyResult())
        }
    }

    internal override suspend fun dispatchLoadBefore(
        currentBeginIndex: Int, currentBeginItem: Value,
        pageSize: Int
    ) : CoroutinePageResult<Value> {
        val key = previousKey
        if (key != null) {
            val afterResult = loadBefore(LoadParams(key, pageSize))
            return LoadResultHandler(this, PageResult.PREPEND).getPageResult(afterResult)
        } else {
            return CoroutinePageResult(PageResult.PREPEND, PageResult.getEmptyResult())
        }
    }

    /**
     * Load initial data.
     *
     *
     * This method is called first to initialize a PagedList with data. If it's possible to count
     * the items that can be loaded by the DataSource, it's recommended to return the loaded data
     * with the five-parameter constructor of
     * [InitialResult]. This enables PagedLists
     * presenting data from this source to display placeholders to represent unloaded items.
     *
     *
     * [LoadInitialParams.requestedLoadSize] is a hint, not a requirement, so it may be may be
     * altered or ignored.
     *
     * @param params Parameters for initial load, including requested load size.
     */
    abstract suspend fun loadInitial(
        params: LoadInitialParams<Key>
    ) : CoroutinePageKeyedDataSource.InitialResult<Key, Value>

    /**
     * Prepend page with the key specified by [LoadParams.key].
     *
     *
     * It's valid to return a different list size than the page size if it's easier, e.g. if your
     * backend defines page sizes. It is generally safer to increase the number loaded than reduce.
     *
     *
     * Data may be return synchronously during the load method, or deferred and called at a
     * later time. Further loads going down will be blocked until this call return a value
     *
     *
     * If data cannot be loaded (for example, if the request is invalid, or the data would be stale
     * and inconsistent, it is valid to call [.invalidate] to invalidate the data source,
     * and prevent further loading. In this case you should throw an exception, but this exception
     * wont use anywhere.
     *
     * @param params Parameters for the load, including the key for the new page, and requested load
     * size.
     */
    abstract suspend fun loadBefore(
        params: LoadParams<Key>
    ) : CoroutinePageKeyedDataSource.LoadResult<Key, Value>

    /**
     * Append page with the key specified by [LoadParams.key].
     *
     *
     * It's valid to return a different list size than the page size if it's easier, e.g. if your
     * backend defines page sizes. It is generally safer to increase the number loaded than reduce.
     *
     *
     * Data may be return synchronously during the load method, or deferred and called at a
     * later time. Further loads going down will be blocked until this call return a value.
     *
     *
     * If data cannot be loaded (for example, if the request is invalid, or the data would be stale
     * and inconsistent, it is valid to call [.invalidate] to invalidate the data source,
     * and prevent further loading. In this case you should throw an exception, but this exception
     * wont use anywhere.
     *
     * @param params Parameters for the load, including the key for the new page, and requested load
     * size.
     */
    abstract suspend fun loadAfter(
        params: LoadParams<Key>
    ) : CoroutinePageKeyedDataSource.LoadResult<Key, Value>

    override fun <ToValue> mapByPage(
        function: Function<List<Value>, List<ToValue>>
    ): CoroutinePageKeyedDataSource<Key, ToValue> {
        return CoroutineWrapperPageKeyedDataSource<Key, Value, ToValue>(
            this,
            function
        )
    }

    override fun <ToValue> map(
        function: Function<Value, ToValue>
    ): CoroutinePageKeyedDataSource<Key, ToValue> {
        return mapByPage<ToValue>(
            createListFunction<Value, ToValue>(
                function
            )
        )
    }
}
