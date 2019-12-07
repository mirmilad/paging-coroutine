package androidx.paging

import android.R.attr
import androidx.annotation.WorkerThread
import java.util.concurrent.Executor


abstract class CoroutinePagedList<T> : PagedList<T> {

    internal constructor(
        pagedStorage: PagedStorage<T>,
        mainThreadExecutor: Executor,
        boundaryCallback: BoundaryCallback<T>?,
        config: Config
    ) :
            super(
                pagedStorage,
                mainThreadExecutor,
                dummyExecutor,
                boundaryCallback, config
            ) {
    }

    companion object {

        private val dummyExecutor = object : Executor {
            override fun execute(p0: Runnable) {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }
        }

        /**
         * Create a PagedList which loads data from the provided data source on a coroutine,
         * posting updates to the main thread.
         *
         *
         * @param dataSource DataSource providing data to the PagedList
         * @param notifyExecutor Thread that will use and consume data from the PagedList.
         *                       Generally, this is the UI/main thread.
         * @param boundaryCallback Optional boundary callback to attach to the list.
         * @param config PagedList Config, which defines how the PagedList will load data.
         * @param <K> Key type that indicates to the DataSource what data to load.
         * @param <T> Type of items to be held and loaded by the PagedList.
         *
         * @return Newly created PagedList, which will page in data from the DataSource as needed.
        </T></K> */
        internal/* synthetic access */ fun <K, T> create(
            dataSource: DataSource<K, T>,
            notifyExecutor: Executor,
            boundaryCallback: BoundaryCallback<T>?,
            config: Config,
            key: K?
        ): PagedList<T> {

            if(dataSource !is CoroutineDataSource<K, T>)
                throw IllegalArgumentException("dataSource param must be child of CoroutineDataSource")

            var dataSourceVar = dataSource
            return if (dataSourceVar.isContiguous || !config.enablePlaceholders) {
                var lastLoad = ContiguousPagedList.LAST_LOAD_UNSPECIFIED
                if (!dataSourceVar.isContiguous) {
                    dataSourceVar = (dataSourceVar as CoroutinePositionalDataSource<T>)
                        .wrapAsContiguousWithoutPlaceholders() as CoroutineDataSource<K, T>
                    if (key != null) {
                        lastLoad = key as Int
                    }
                }
                val contigDataSource =
                    dataSourceVar as CoroutineContiguousDataSource<K, T>
                CoroutineContiguousPagedList<K, T>(
                    contigDataSource,
                    notifyExecutor,
                    boundaryCallback,
                    config,
                    key,
                    lastLoad
                )
            } else {
                CoroutineTiledPagedList(
                    (dataSource as CoroutinePositionalDataSource<T>),
                    notifyExecutor,
                    boundaryCallback,
                    config,
                    if (attr.key != null) attr.key else 0
                )
            }
        }
    }

    class Builder<Key, Value>
    /**
     * Create a PagedList.Builder with the provided [DataSource] and [Config].
     *
     * @param dataSource DataSource the PagedList will load from.
     * @param config Config that defines how the PagedList loads data from its DataSource.
     */
        (
        private val mDataSource: DataSource<Key, Value>,
        private val mConfig: Config
    ) {
        private var mNotifyExecutor: Executor? = null
        private var mBoundaryCallback: BoundaryCallback<Value>? = null
        private var mInitialKey: Key? = null

        init {

            requireNotNull(mDataSource) { "DataSource may not be null" }

            requireNotNull(mConfig) { "Config may not be null" }
        }

        /**
         * Create a PagedList.Builder with the provided [DataSource] and page size.
         *
         *
         * This method is a convenience for:
         * <pre>
         * PagedList.Builder(dataSource,
         * new PagedList.Config.Builder().setPageSize(pageSize).build());
        </pre> *
         *
         * @param dataSource DataSource the PagedList will load from.
         * @param pageSize Config that defines how the PagedList loads data from its DataSource.
         */
        constructor(dataSource: DataSource<Key, Value>, pageSize: Int) : this(
            dataSource,
            Config.Builder().setPageSize(pageSize).build()
        ) {
        }

        /**
         * The executor defining where page loading updates are dispatched.
         *
         * @param notifyExecutor Executor that receives PagedList updates, and where
         * [Callback] calls are dispatched. Generally, this is the ui/main thread.
         * @return this
         */
        fun setNotifyExecutor(notifyExecutor: Executor): Builder<Key, Value> {
            mNotifyExecutor = notifyExecutor
            return this
        }
        /**
         * The BoundaryCallback for out of data events.
         *
         *
         * Pass a BoundaryCallback to listen to when the PagedList runs out of data to load.
         *
         * @param boundaryCallback BoundaryCallback for listening to out-of-data events.
         * @return this
         */
        fun setBoundaryCallback(
            boundaryCallback: PagedList.BoundaryCallback<Value>?
        ): Builder<Key, Value> {
            mBoundaryCallback = boundaryCallback
            return this
        }

        /**
         * Sets the initial key the DataSource should load around as part of initialization.
         *
         * @param initialKey Key the DataSource should load around as part of initialization.
         * @return this
         */
        fun setInitialKey(initialKey: Key?): Builder<Key, Value> {
            mInitialKey = initialKey
            return this
        }

        /**
         * Creates a [PagedList] with the given parameters.
         *
         *
         * This call will dispatch the [DataSource]'s loadInitial method immediately. If a
         * DataSource posts all of its work (e.g. to a network thread), the PagedList will
         * be immediately created as empty, and grow to its initial size when the initial load
         * completes.
         *
         *
         * If the DataSource implements its load synchronously, doing the load work immediately in
         * the loadInitial method, the PagedList will block on that load before completing
         * construction. In this case, use a background thread to create a PagedList.
         *
         *
         * It's fine to create a PagedList with an async DataSource on the main thread, such as in
         * the constructor of a ViewModel. An async network load won't block the initialLoad
         * function. For a synchronous DataSource such as one created from a Room database, a
         * `LiveData<PagedList>` can be safely constructed with [LivePagedListBuilder]
         * on the main thread, since actual construction work is deferred, and done on a background
         * thread.
         *
         *
         * While build() will always return a PagedList, it's important to note that the PagedList
         * initial load may fail to acquire data from the DataSource. This can happen for example if
         * the DataSource is invalidated during its initial load. If this happens, the PagedList
         * will be immediately [detached][PagedList.isDetached], and you can retry
         * construction (including setting a new DataSource).
         *
         * @return The newly constructed PagedList
         */
        @WorkerThread
        fun build(): PagedList<Value> {
            // TODO: define defaults, once they can be used in module without android dependency
            requireNotNull(mNotifyExecutor) { "MainThreadExecutor required" }

            return CoroutinePagedList.create(
                mDataSource,
                mNotifyExecutor!!,
                mBoundaryCallback,
                mConfig,
                mInitialKey
            )
        }
    }
}
