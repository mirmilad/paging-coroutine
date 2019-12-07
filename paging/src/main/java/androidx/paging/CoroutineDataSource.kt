package androidx.paging

import androidx.lifecycle.LiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

abstract class CoroutineDataSource<K, V> : DataSource<K, V>() {

    internal data class CoroutinePageResult<T>(val type: Int, val pageResult: PageResult<T>) {}

    class No

    private val completableJob = Job()
    val coroutineScope = CoroutineScope(Dispatchers.Default + completableJob)

    override fun invalidate() {
        super.invalidate()
        completableJob.cancel()
    }

    abstract class Factory<K, V> : DataSource.Factory<K, V>() {

        /**
         * Constructs a `LiveData<PagedList>`, from this `CoroutineDataSource.Factory`, convenience for
         * [CoroutineLivePagedListBuilder].
         *
         * No work (such as loading) is done immediately, the creation of the first PagedList is is
         * deferred until the LiveData is observed.
         *
         * @param config Paging configuration.
         * @param initialLoadKey Initial load key passed to the first PagedList/CoroutineDataSource.
         * @param boundaryCallback The boundary callback for listening to PagedList load state.
         *
         * @see CoroutineLivePagedListBuilder
         */
        fun toCoroutineLiveData(
            config: PagedList.Config,
            initialLoadKey: K? = null,
            boundaryCallback: PagedList.BoundaryCallback<V>? = null
        ): LiveData<PagedList<V>> {
            return CoroutineLivePagedListBuilder(this, config)
                .setInitialLoadKey(initialLoadKey)
                .setBoundaryCallback(boundaryCallback)
                .build()
        }

        /**
         * Constructs a `LiveData<PagedList>`, from this `CoroutineDataSource.Factory`, convenience for
         * [CoroutineLivePagedListBuilder].
         *
         * No work (such as loading) is done immediately, the creation of the first PagedList is is
         * deferred until the LiveData is observed.
         *
         * @param pageSize Page size.
         * @param initialLoadKey Initial load key passed to the first PagedList/CoroutineDataSource.
         * @param boundaryCallback The boundary callback for listening to PagedList load state.
         *
         * @see CoroutineLivePagedListBuilder
         */
        fun toCoroutineLiveData(
            pageSize: Int,
            initialLoadKey: K? = null,
            boundaryCallback: PagedList.BoundaryCallback<V>? = null
        ): LiveData<PagedList<V>> {
            return CoroutineLivePagedListBuilder(this, Config(pageSize))
                .setInitialLoadKey(initialLoadKey)
                .setBoundaryCallback(boundaryCallback)
                .build()
        }
    }
}