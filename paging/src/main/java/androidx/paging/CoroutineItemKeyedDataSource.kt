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

import androidx.arch.core.util.Function
import androidx.paging.PageResult.INIT
import androidx.paging.PageResult.ResultType

/**
 * Incremental data loader for paging keyed content, where loaded content uses previously loaded
 * items as input to future loads.
 *
 *
 * Implement a DataSource using ItemKeyedDataSource if you need to use data from item `N - 1`
 * to load item `N`. This is common, for example, in sorted database queries where
 * attributes of the item such just before the next query define how to execute it.
 *
 *
 * The `InMemoryByItemRepository` in the
 * [PagingWithNetworkSample](https://github.com/googlesamples/android-architecture-components/blob/master/PagingWithNetworkSample/README.md)
 * shows how to implement a network ItemKeyedDataSource using
 * [Retrofit](https://square.github.io/retrofit/), while
 * handling swipe-to-refresh, network errors, and retry.
 *
 * @param <Key> Type of data used to query Value types out of the DataSource.
 * @param <Value> Type of items being loaded by the DataSource.
</Value></Key> */
abstract class CoroutineItemKeyedDataSource<Key, Value> :
    CoroutineContiguousDataSource<Key, Value>() {
    /**
     * Holder object for inputs to [.loadInitial].
     *
     * @param <Key> Type of data used to query Value types out of the DataSource.
    </Key> */
    class LoadInitialParams<Key>(
        /**
         * Load items around this key, or at the beginning of the data set if `null` is
         * passed.
         *
         *
         * Note that this key is generally a hint, and may be ignored if you want to always load
         * from the beginning.
         */
        val requestedInitialKey: Key?,
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
     * Holder object for inputs to [.loadBefore]
     * and [.loadAfter].
     *
     * @param <Key> Type of data used to query Value types out of the DataSource.
    </Key> */
    class LoadParams<Key>(
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
     * Callback for [.loadInitial]
     * to return data and, optionally, position/count information.
     *
     *
     * A callback can be called only once, and will throw if called again.
     *
     *
     * If you can compute the number of items in the data set before and after the loaded range,
     * call the three parameter [.onResult] to pass that information. You
     * can skip passing this information by calling the single parameter [.onResult],
     * either if it's difficult to compute, or if [placeholdersEnabled] is
     * `false`, so the positioning information will be ignored.
     *
     *
     * It is always valid for a DataSource loading method that takes a callback to stash the
     * callback and call it later. This enables DataSources to be fully asynchronous, and to handle
     * temporary, recoverable error states (such as a network error that can be retried).
     *
     * @param <Value> Type of items being loaded.
    </Value> */
    abstract class LoadInitialCallback<Value> : LoadCallback<Value>() {
        /**
         * Called to pass initial load state from a DataSource.
         *
         *
         * Call this method from your DataSource's `loadInitial` function to return data,
         * and inform how many placeholders should be shown before and after. If counting is cheap
         * to compute (for example, if a network load returns the information regardless), it's
         * recommended to pass data back through this method.
         *
         *
         * It is always valid to pass a different amount of data than what is requested. Pass an
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
        internal abstract fun onResult(
            data: List<Value>,
            position: Int,
            totalCount: Int
        ) : CoroutinePageResult<Value>
    }
    sealed class InitialResult<out Value> {
        data class Success<out Value>(val data : List<Value>, val position: Int? = null, val totalCount: Int? = null) : InitialResult<Value>()

        data class Error(val throwable: Throwable) : InitialResult<Nothing>()

        object None : InitialResult<Nothing>()
    }

    /**
     * Callback for ItemKeyedDataSource [.loadBefore]
     * and [.loadAfter] to return data.
     *
     *
     * A callback can be called only once, and will throw if called again.
     *
     *
     * It is always valid for a DataSource loading method that takes a callback to stash the
     * callback and call it later. This enables DataSources to be fully asynchronous, and to handle
     * temporary, recoverable error states (such as a network error that can be retried).
     *
     * @param <Value> Type of items being loaded.
    </Value> */
    abstract class LoadCallback<Value> {
        /**
         * Called to pass loaded data from a DataSource.
         *
         *
         * Call this method from your ItemKeyedDataSource's
         * [.loadBefore] and
         * [.loadAfter] methods to return data.
         *
         *
         * Call this from [.loadInitial] to
         * initialize without counting available data, or supporting placeholders.
         *
         *
         * It is always valid to pass a different amount of data than what is requested. Pass an
         * empty list if there is no more data to load.
         *
         * @param data List of items loaded from the ItemKeyedDataSource.
         */
        internal abstract suspend fun onResult(data: List<Value>) : CoroutinePageResult<Value>
    }
    sealed class LoadResult<out Value> {
        data class Success<out Value>(val data: List<Value>) : LoadResult<Value>()

        data class Error(val throwable: Throwable) : LoadResult<Nothing>()

        object None : LoadResult<Nothing>()
    }

    internal class LoadInitialCallbackImpl<Value>(
        private val mDataSource: CoroutineItemKeyedDataSource<*, *>,
        private val mCountingEnabled: Boolean
    ) :
        LoadInitialCallback<Value>() {

        override fun onResult(
            data: List<Value>,
            position: Int,
            totalCount: Int
        ) : CoroutinePageResult<Value> {

            if (mDataSource.isInvalid) {
                return CoroutinePageResult.Success(INIT, PageResult.getInvalidResult())
            }

            LoadCallbackHelper.validateInitialLoadParams(data, position, totalCount)
            val trailingUnloadedCount = totalCount - position - data.size
            return if (mCountingEnabled) {
                CoroutinePageResult.Success(
                    INIT,
                    PageResult(
                        data, position, trailingUnloadedCount, 0
                    )
                )
            } else {
                CoroutinePageResult.Success(INIT, PageResult(data, position))
            }
        }

        override suspend fun onResult(data: List<Value>) : CoroutinePageResult<Value> {
            if (mDataSource.isInvalid) {
                return CoroutinePageResult.Success(INIT, PageResult.getInvalidResult())
            }

            return CoroutinePageResult.Success(INIT, PageResult(data, 0, 0, 0))
        }
    }

    internal class LoadCallbackImpl<Value>(
        private val mDataSource: CoroutineItemKeyedDataSource<*, *>,
        @ResultType private val mType: Int
    ) :
        LoadCallback<Value>() {

        override suspend fun onResult(data: List<Value>) : CoroutinePageResult<Value> {
            if (mDataSource.isInvalid) {
                return CoroutinePageResult.Success(mType, PageResult.getInvalidResult())
            }

            return CoroutinePageResult.Success(mType, PageResult(data, 0, 0, 0))
        }
    }

    override fun getKey(position: Int, item: Value?): Key? {
        return item?.let { getKey(it) }
    }

    override suspend fun dispatchLoadInitial(
        key: Key?,
        initialLoadSize: Int,
        pageSize: Int,
        enablePlaceholders: Boolean
    ): CoroutinePageResult<Value> {
        val callback = LoadInitialCallbackImpl<Value>(
                this,
                enablePlaceholders)

        return loadInitial(
            LoadInitialParams(
                key,
                initialLoadSize,
                enablePlaceholders
            )
        ).run {
            when (this) {
                InitialResult.None -> CoroutinePageResult.None
                is InitialResult.Error -> CoroutinePageResult.Error(throwable)
                is InitialResult.Success -> {
                    if (position != null && totalCount != null)
                        callback.onResult(data, position, totalCount)
                    else
                        callback.onResult(data)

                }
            }
        }
    }

    override suspend fun dispatchLoadAfter(
        currentEndIndex: Int,
        currentEndItem: Value,
        pageSize: Int
    ): CoroutinePageResult<Value> {
        val callback = LoadCallbackImpl<Value>(this, PageResult.APPEND)
        return loadAfter(
            LoadParams(getKey(currentEndItem), pageSize)
        ).run {
            when (this) {
                LoadResult.None -> CoroutinePageResult.None
                is LoadResult.Error -> CoroutinePageResult.Error(throwable)
                is LoadResult.Success -> callback.onResult(data)
            }
        }
    }

    override suspend fun dispatchLoadBefore(
        currentBeginIndex: Int,
        currentBeginItem: Value,
        pageSize: Int
    ): CoroutinePageResult<Value> {
        val callback = LoadCallbackImpl<Value>(this, PageResult.PREPEND)
        return loadBefore(
            LoadParams(getKey(currentBeginItem), pageSize)
        ).run {
            when (this) {
                LoadResult.None -> CoroutinePageResult.None
                is LoadResult.Error -> CoroutinePageResult.Error(throwable)
                is LoadResult.Success -> callback.onResult(data)
            }
        }
    }

    /**
     * Load initial data.
     *
     *
     * This method is called first to initialize a PagedList with data. If it's possible to count
     * the items that can be loaded by the DataSource, it's recommended to pass the loaded data to
     * the callback via the three-parameter
     * [LoadInitialCallback.onResult]. This enables PagedLists
     * presenting data from this source to display placeholders to represent unloaded items.
     *
     *
     * [requestedInitialKey] and [requestedLoadSize]
     * are hints, not requirements, so they may be altered or ignored. Note that ignoring the
     * `requestedInitialKey` can prevent subsequent PagedList/DataSource pairs from
     * initializing at the same location. If your data source never invalidates (for example,
     * loading from the network without the network ever signalling that old data must be reloaded),
     * it's fine to ignore the `initialLoadKey` and always start from the beginning of the
     * data set.
     *
     * @param params Parameters for initial load, including initial key and requested size.
     * @param callback Callback that receives initial load data.
     */
    abstract suspend fun loadInitial(
        params: LoadInitialParams<Key>
    ) : InitialResult<Value>

    /**
     * Load list data after the key specified in [key].
     *
     *
     * It's valid to return a different list size than the page size if it's easier, e.g. if your
     * backend defines page sizes. It is generally safer to increase the number loaded than reduce.
     *
     *
     * Data may be passed synchronously during the loadAfter method, or deferred and called at a
     * later time. Further loads going down will be blocked until the callback is called.
     *
     *
     * If data cannot be loaded (for example, if the request is invalid, or the data would be stale
     * and inconsistent, it is valid to call [.invalidate] to invalidate the data source,
     * and prevent further loading.
     *
     * @param params Parameters for the load, including the key to load after, and requested size.
     * @param callback Callback that receives loaded data.
     */
    abstract suspend fun loadAfter(
        params: LoadParams<Key>
    ) : LoadResult<Value>

    /**
     * Load list data before the key specified in [key].
     *
     *
     * It's valid to return a different list size than the page size if it's easier, e.g. if your
     * backend defines page sizes. It is generally safer to increase the number loaded than reduce.
     *
     *
     *
     * **Note:** Data returned will be prepended just before the key
     * passed, so if you vary size, ensure that the last item is adjacent to the passed key.
     *
     *
     * Data may be passed synchronously during the loadBefore method, or deferred and called at a
     * later time. Further loads going up will be blocked until the callback is called.
     *
     *
     * If data cannot be loaded (for example, if the request is invalid, or the data would be stale
     * and inconsistent, it is valid to call [.invalidate] to invalidate the data source,
     * and prevent further loading.
     *
     * @param params Parameters for the load, including the key to load before, and requested size.
     * @param callback Callback that receives loaded data.
     */
    abstract suspend fun loadBefore(
        params: LoadParams<Key>
    ) : LoadResult<Value>

    /**
     * Return a key associated with the given item.
     *
     *
     * If your ItemKeyedDataSource is loading from a source that is sorted and loaded by a unique
     * integer ID, you would return `item.getID()` here. This key can then be passed to
     * [.loadBefore] or
     * [.loadAfter] to load additional items adjacent to the item
     * passed to this function.
     *
     *
     * If your key is more complex, such as when you're sorting by name, then resolving collisions
     * with integer ID, you'll need to return both. In such a case you would use a wrapper class,
     * such as `Pair<String, Integer>` or, in Kotlin,
     * `data class Key(val name: String, val id: Int)`
     *
     * @param item Item to get the key from.
     * @return Key associated with given item.
     */
    abstract fun getKey(item: Value): Key

    override fun <ToValue> mapByPage(
        function: Function<List<Value>, List<ToValue>>
    ): CoroutineItemKeyedDataSource<Key, ToValue> {
        return CoroutineWrapperItemKeyedDataSource(this, function)
    }

    override fun <ToValue> map(
        function: Function<Value, ToValue>
    ): CoroutineItemKeyedDataSource<Key, ToValue> {
        return mapByPage(createListFunction(function))
    }
}