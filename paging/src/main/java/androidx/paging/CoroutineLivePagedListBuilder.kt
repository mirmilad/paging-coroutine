package androidx.paging

import android.annotation.SuppressLint
import android.renderscript.Sampler
import android.util.Log
import androidx.annotation.AnyThread
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.lifecycle.ComputableLiveData
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor


/**
 * Builder for `LiveData<PagedList>`, given a [CoroutineDataSource.Factory] and a
 * [PagedList.Config].
 *
 *
 * The required parameters are in the constructor, so you can simply construct and build, or
 * optionally enable extra features (such as initial load key, or BoundaryCallback).
 *
 * @param <Key> Type of input valued used to load data from the CoroutineDataSource. Must be integer if
 * you're using PositionalDataSourceKtx.
 * @param <Value> Item type being presented.
</Value></Key> */
class CoroutineLivePagedListBuilder<Key, Value>
/**
 * Creates a CoroutineLivePagedListBuilder with required parameters.
 *
 * @param dataSourceFactory CoroutineDataSource factory providing CoroutineDataSource generations.
 * @param config Paging configuration.
 */
    (
    private val mDataSourceFactory: DataSource.Factory<Key, Value>,
    private val mConfig: PagedList.Config
) {
    private var mInitialLoadKey: Key? = null
    private var mBoundaryCallback: PagedList.BoundaryCallback<Value>? = null

    init {

        requireNotNull(mConfig) { "PagedList.Config must be provided" }
        requireNotNull(mDataSourceFactory) { "CoroutineDataSource.Factory must be provided" }
    }

    /**
     * Creates a CoroutineLivePagedListBuilder with required parameters.
     *
     *
     * This method is a convenience for:
     * <pre>
     * CoroutineLivePagedListBuilder(dataSourceFactory,
     * new PagedList.Config.Builder().setPageSize(pageSize).build())
    </pre> *
     *
     * @param dataSourceFactory CoroutineDataSource.Factory providing CoroutineDataSource generations.
     * @param pageSize Size of pages to load.
     */
    constructor(
        dataSourceFactory: DataSource.Factory<Key, Value>,
        pageSize: Int
    ) : this(dataSourceFactory, PagedList.Config.Builder().setPageSize(pageSize).build()) {
    }

    /**
     * First loading key passed to the first PagedList/CoroutineDataSource.
     *
     *
     * When a new PagedList/CoroutineDataSource pair is created after the first, it acquires a load key from
     * the previous generation so that data is loaded around the position already being observed.
     *
     * @param key Initial load key passed to the first PagedList/CoroutineDataSource.
     * @return this
     */
    fun setInitialLoadKey(key: Key?): CoroutineLivePagedListBuilder<Key, Value> {
        mInitialLoadKey = key
        return this
    }

    /**
     * Sets a [PagedList.BoundaryCallback] on each PagedList created, typically used to load
     * additional data from network when paging from local storage.
     *
     *
     * Pass a BoundaryCallback to listen to when the PagedList runs out of data to load. If this
     * method is not called, or `null` is passed, you will not be notified when each
     * CoroutineDataSource runs out of data to provide to its PagedList.
     *
     *
     * If you are paging from a CoroutineDataSource.Factory backed by local storage, you can set a
     * BoundaryCallback to know when there is no more information to page from local storage.
     * This is useful to page from the network when local storage is a cache of network data.
     *
     *
     * Note that when using a BoundaryCallback with a `LiveData<PagedList>`, method calls
     * on the callback may be dispatched multiple times - one for each PagedList/CoroutineDataSource
     * pair. If loading network data from a BoundaryCallback, you should prevent multiple
     * dispatches of the same method from triggering multiple simultaneous network loads.
     *
     * @param boundaryCallback The boundary callback for listening to PagedList load state.
     * @return this
     */
    fun setBoundaryCallback(
        boundaryCallback: PagedList.BoundaryCallback<Value>?
    ): CoroutineLivePagedListBuilder<Key, Value> {
        mBoundaryCallback = boundaryCallback
        return this
    }


    /**
     * Constructs the `LiveData<PagedList>`.
     *
     *
     * No work (such as loading) is done immediately, the creation of the first PagedList is is
     * deferred until the LiveData is observed.
     *
     * @return The LiveData of PagedLists
     */
    @SuppressLint("RestrictedApi")
    fun build(): LiveData<PagedList<Value>> {
        return create(
            mInitialLoadKey, mConfig, mBoundaryCallback, mDataSourceFactory,
            ArchTaskExecutor.getMainThreadExecutor()
        )
    }

    @AnyThread
    @SuppressLint("RestrictedApi")
    private fun <Key, Value> create(
        initialLoadKey: Key?,
        config: PagedList.Config,
        boundaryCallback: PagedList.BoundaryCallback<Value>?,
        dataSourceFactory: DataSource.Factory<Key, Value>,
        notifyExecutor: Executor
    ): LiveData<PagedList<Value>> = object {

        private lateinit var mDataSource: DataSource<Key, Value>
        private lateinit var mList: PagedList<Value>
        private val onInvalidChannel = Channel<Boolean>()

        private val mCallback = DataSource.InvalidatedCallback { onInvalidChannel.offer(true) }

        private suspend fun updatePagedList() = withContext(Dispatchers.Default) {
            var initializeKey = initialLoadKey
            do {
                if(::mList.isInitialized)
                    initializeKey = mList.lastKey as Key?

                if (::mDataSource.isInitialized) {
                    mDataSource.removeInvalidatedCallback(mCallback)
                }

                mDataSource = dataSourceFactory.create()
                mDataSource.addInvalidatedCallback(mCallback)

                mList = CoroutinePagedList.Builder(mDataSource, config)
                    .setNotifyExecutor(notifyExecutor)
                    .setBoundaryCallback(boundaryCallback)
                    .setInitialKey(initializeKey)
                    .build()
            } while (mList.isDetached())
        }

        val liveData = liveData {
            try {
                var update = !::mList.isInitialized
                while (true) {
                    if(update)
                        updatePagedList()
                    emit(mList)
                    update = onInvalidChannel.receive()
                }
            } finally {
                Log.d("LFC", "live data canceled")
            }
        }
    }.liveData

}
