package androidx.paging

import androidx.lifecycle.LiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

abstract class CoroutineDataSource<K, V> : DataSource<K, V>() {

    internal sealed class CoroutinePageResult<out T> {
        data class Success<T>(val type: Int, val pageResult: PageResult<T>) : CoroutinePageResult<T>() {}

        data class Error(val throwable: Throwable) : CoroutinePageResult<Nothing>()

        object None : CoroutinePageResult<Nothing>()
    }

    private val completableJob = Job()
    val coroutineScope = CoroutineScope(Dispatchers.Default + completableJob)

    override fun invalidate() {
        cancelCoroutines()
        super.invalidate()
    }

    fun cancelCoroutines() {
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