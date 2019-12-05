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

import androidx.annotation.GuardedBy
import androidx.arch.core.util.Function
import androidx.paging.DataSource.LoadCallbackHelper
import androidx.paging.PageResult.ResultType
import java.util.concurrent.Executor

/**
 * Incremental data loader for page-keyed content, where requests return keys for next/previous
 * pages.
 *
 *
 * Implement a DataSource using PageKeyedDataSource if you need to use data from page `N - 1`
 * to load page `N`. This is common, for example, in network APIs that include a next/previous
 * link or key with each page load.
 *
 *
 * The `InMemoryByPageRepository` in the
 * [PagingWithNetworkSample](https://github.com/googlesamples/android-architecture-components/blob/master/PagingWithNetworkSample/README.md)
 * shows how to implement a network PageKeyedDataSource using
 * [Retrofit](https://square.github.io/retrofit/), while
 * handling swipe-to-refresh, network errors, and retry.
 *
 * @param <Key> Type of data used to query Value types out of the DataSource.
 * @param <Value> Type of items being loaded by the DataSource.
</Value></Key> */
internal abstract class PageKeyedDataSource<Key, Value> :
    ContiguousDataSource<Key, Value>() {
    private val mKeyLock = Any()
    @GuardedBy("mKeyLock")
    private var mNextKey: Key? = null
    @GuardedBy("mKeyLock")
    private var mPreviousKey: Key? = null

    fun  /* synthetic access */initKeys(previousKey: Key?, nextKey: Key?) {
        synchronized(mKeyLock) {
            mPreviousKey = previousKey
            mNextKey = nextKey
        }
    }

    /* synthetic access */
    private var previousKey: Key?
        private get() {
            synchronized(mKeyLock) { return mPreviousKey }
        }
        set(previousKey) {
            synchronized(mKeyLock) { mPreviousKey = previousKey }
        }

    /* synthetic access */
    private var nextKey: Key?
        private get() {
            synchronized(mKeyLock) { return mNextKey }
        }
        set(nextKey) {
            synchronized(mKeyLock) { mNextKey = nextKey }
        }

    /**
     * Holder object for inputs to [.loadInitial].
     *
     * @param <Key> Type of data used to query pages.
    </Key> */
    class LoadInitialParams<Key>(
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
     * call the five parameter [.onResult] to pass that
     * information. You can skip passing this information by calling the three parameter
     * [.onResult], either if it's difficult to compute, or if
     * [placeholdersEnabled] is `false`, so the positioning
     * information will be ignored.
     *
     *
     * It is always valid for a DataSource loading method that takes a callback to stash the
     * callback and call it later. This enables DataSources to be fully asynchronous, and to handle
     * temporary, recoverable error states (such as a network error that can be retried).
     *
     * @param <Key> Type of data used to query pages.
     * @param <Value> Type of items being loaded.
    </Value></Key> */
    abstract class LoadInitialCallback<Key, Value> {
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
        abstract fun onResult(
            data: List<Value>, position: Int, totalCount: Int,
            previousPageKey: Key?, nextPageKey: Key?
        )

        /**
         * Called to pass loaded data from a DataSource.
         *
         *
         * Call this from [.loadInitial] to
         * initialize without counting available data, or supporting placeholders.
         *
         *
         * It is always valid to pass a different amount of data than what is requested. Pass an
         * empty list if there is no more data to load.
         *
         * @param data List of items loaded from the PageKeyedDataSource.
         * @param previousPageKey Key for page before the initial load result, or `null` if no
         * more data can be loaded before.
         * @param nextPageKey Key for page after the initial load result, or `null` if no
         * more data can be loaded after.
         */
        abstract fun onResult(
            data: List<Value>, previousPageKey: Key?,
            nextPageKey: Key?
        )
    }

    /**
     * Callback for PageKeyedDataSource [.loadBefore] and
     * [.loadAfter] to return data.
     *
     *
     * A callback can be called only once, and will throw if called again.
     *
     *
     * It is always valid for a DataSource loading method that takes a callback to stash the
     * callback and call it later. This enables DataSources to be fully asynchronous, and to handle
     * temporary, recoverable error states (such as a network error that can be retried).
     *
     * @param <Key> Type of data used to query pages.
     * @param <Value> Type of items being loaded.
    </Value></Key> */
    abstract class LoadCallback<Key, Value> {
        /**
         * Called to pass loaded data from a DataSource.
         *
         *
         * Call this method from your PageKeyedDataSource's
         * [.loadBefore] and
         * [.loadAfter] methods to return data.
         *
         *
         * It is always valid to pass a different amount of data than what is requested. Pass an
         * empty list if there is no more data to load.
         *
         *
         * Pass the key for the subsequent page to load to adjacentPageKey. For example, if you've
         * loaded a page in [.loadBefore], pass the key for the
         * previous page, or `null` if the loaded page is the first. If in
         * [.loadAfter], pass the key for the next page, or
         * `null` if the loaded page is the last.
         *
         * @param data List of items loaded from the PageKeyedDataSource.
         * @param adjacentPageKey Key for subsequent page load (previous page in [.loadBefore]
         * / next page in [.loadAfter]), or `null` if there are
         * no more pages to load in the current load direction.
         */
        abstract fun onResult(
            data: List<Value>,
            adjacentPageKey: Key?
        )
    }

    internal class LoadInitialCallbackImpl<Key, Value>(
        dataSource: PageKeyedDataSource<Key, Value>,
        countingEnabled: Boolean, receiver: PageResult.Receiver<Value>
    ) :
        LoadInitialCallback<Key, Value>() {
        val mCallbackHelper: LoadCallbackHelper<Value>
        private val mDataSource: PageKeyedDataSource<Key, Value>
        private val mCountingEnabled: Boolean
        override fun onResult(
            data: List<Value>, position: Int, totalCount: Int,
            previousPageKey: Key?, nextPageKey: Key?
        ) {
            if (!mCallbackHelper.dispatchInvalidResultIfInvalid()) {
                LoadCallbackHelper.validateInitialLoadParams(data, position, totalCount)
                // setup keys before dispatching data, so guaranteed to be ready
                mDataSource.initKeys(previousPageKey, nextPageKey)
                val trailingUnloadedCount = totalCount - position - data.size
                if (mCountingEnabled) {
                    mCallbackHelper.dispatchResultToReceiver(
                        PageResult(
                            data, position, trailingUnloadedCount, 0
                        )
                    )
                } else {
                    mCallbackHelper.dispatchResultToReceiver(PageResult(data, position))
                }
            }
        }

        override fun onResult(
            data: List<Value>, previousPageKey: Key?,
            nextPageKey: Key?
        ) {
            if (!mCallbackHelper.dispatchInvalidResultIfInvalid()) {
                mDataSource.initKeys(previousPageKey, nextPageKey)
                mCallbackHelper.dispatchResultToReceiver(PageResult(data, 0, 0, 0))
            }
        }

        init {
            mCallbackHelper = LoadCallbackHelper(
                dataSource, PageResult.INIT, null, receiver
            )
            mDataSource = dataSource
            mCountingEnabled = countingEnabled
        }
    }

    internal class LoadCallbackImpl<Key, Value>(
        dataSource: PageKeyedDataSource<Key, Value>,
        @ResultType type: Int, mainThreadExecutor: Executor?,
        receiver: PageResult.Receiver<Value>
    ) :
        LoadCallback<Key, Value>() {
        val mCallbackHelper: LoadCallbackHelper<Value>
        private val mDataSource: PageKeyedDataSource<Key, Value>
        override fun onResult(
            data: List<Value>,
            adjacentPageKey: Key?
        ) {
            if (!mCallbackHelper.dispatchInvalidResultIfInvalid()) {
                if (mCallbackHelper.mResultType == PageResult.APPEND) {
                    mDataSource.nextKey = adjacentPageKey
                } else {
                    mDataSource.previousKey = adjacentPageKey
                }
                mCallbackHelper.dispatchResultToReceiver(PageResult(data, 0, 0, 0))
            }
        }

        init {
            mCallbackHelper = LoadCallbackHelper(
                dataSource, type, mainThreadExecutor, receiver
            )
            mDataSource = dataSource
        }
    }

    override fun getKey(
        position: Int,
        item: Value?
    ): Key? { // don't attempt to persist keys, since we currently don't pass them to initial load
        return null
    }

    override fun supportsPageDropping(): Boolean { /* To support page dropping when PageKeyed, we'll need to:
         *    - Stash keys for every page we have loaded (can id by index relative to loadInitial)
         *    - Drop keys for any page not adjacent to loaded content
         *    - And either:
         *        - Allow impl to signal previous page key: onResult(data, nextPageKey, prevPageKey)
         *        - Re-trigger loadInitial, and break assumption it will only occur once.
         */
        return false
    }

    override fun dispatchLoadInitial(
        key: Key?, initialLoadSize: Int, pageSize: Int,
        enablePlaceholders: Boolean, mainThreadExecutor: Executor,
        receiver: PageResult.Receiver<Value>
    ) {
        val callback =
            LoadInitialCallbackImpl(
                this,
                enablePlaceholders,
                receiver
            )
        loadInitial(
            LoadInitialParams(
                initialLoadSize,
                enablePlaceholders
            ), callback
        )
        // If initialLoad's callback is not called within the body, we force any following calls
// to post to the UI thread. This constructor may be run on a background thread, but
// after constructor, mutation must happen on UI thread.
        callback.mCallbackHelper.setPostExecutor(mainThreadExecutor)
    }

    override fun dispatchLoadAfter(
        currentEndIndex: Int, currentEndItem: Value,
        pageSize: Int, mainThreadExecutor: Executor,
        receiver: PageResult.Receiver<Value>
    ) {
        val key = nextKey
        if (key != null) {
            loadAfter(
                LoadParams(key, pageSize),
                LoadCallbackImpl(this, PageResult.APPEND, mainThreadExecutor, receiver)
            )
        } else {
            receiver.onPageResult(PageResult.APPEND, PageResult.getEmptyResult())
        }
    }

    override fun dispatchLoadBefore(
        currentBeginIndex: Int, currentBeginItem: Value,
        pageSize: Int, mainThreadExecutor: Executor,
        receiver: PageResult.Receiver<Value>
    ) {
        val key = previousKey
        if (key != null) {
            loadBefore(
                LoadParams(key, pageSize),
                LoadCallbackImpl(this, PageResult.PREPEND, mainThreadExecutor, receiver)
            )
        } else {
            receiver.onPageResult(PageResult.PREPEND, PageResult.getEmptyResult())
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
     * [requestedLoadSize] is a hint, not a requirement, so it may be may be
     * altered or ignored.
     *
     * @param params Parameters for initial load, including requested load size.
     * @param callback Callback that receives initial load data.
     */
    abstract fun loadInitial(
        params: LoadInitialParams<Key>,
        callback: LoadInitialCallback<Key, Value>
    )

    /**
     * Prepend page with the key specified by [key].
     *
     *
     * It's valid to return a different list size than the page size if it's easier, e.g. if your
     * backend defines page sizes. It is generally safer to increase the number loaded than reduce.
     *
     *
     * Data may be passed synchronously during the load method, or deferred and called at a
     * later time. Further loads going down will be blocked until the callback is called.
     *
     *
     * If data cannot be loaded (for example, if the request is invalid, or the data would be stale
     * and inconsistent, it is valid to call [.invalidate] to invalidate the data source,
     * and prevent further loading.
     *
     * @param params Parameters for the load, including the key for the new page, and requested load
     * size.
     * @param callback Callback that receives loaded data.
     */
    abstract fun loadBefore(
        params: LoadParams<Key>,
        callback: LoadCallback<Key, Value>
    )

    /**
     * Append page with the key specified by [key].
     *
     *
     * It's valid to return a different list size than the page size if it's easier, e.g. if your
     * backend defines page sizes. It is generally safer to increase the number loaded than reduce.
     *
     *
     * Data may be passed synchronously during the load method, or deferred and called at a
     * later time. Further loads going down will be blocked until the callback is called.
     *
     *
     * If data cannot be loaded (for example, if the request is invalid, or the data would be stale
     * and inconsistent, it is valid to call [.invalidate] to invalidate the data source,
     * and prevent further loading.
     *
     * @param params Parameters for the load, including the key for the new page, and requested load
     * size.
     * @param callback Callback that receives loaded data.
     */
    abstract fun loadAfter(
        params: LoadParams<Key>,
        callback: LoadCallback<Key, Value>
    )

    override fun <ToValue> mapByPage(
        function: Function<List<Value>, List<ToValue>>
    ): PageKeyedDataSource<Key, ToValue> {
        return WrapperPageKeyedDataSource(this, function)
    }

    override fun <ToValue> map(
        function: Function<Value, ToValue>
    ): PageKeyedDataSource<Key, ToValue> {
        return mapByPage(createListFunction(function))
    }
}