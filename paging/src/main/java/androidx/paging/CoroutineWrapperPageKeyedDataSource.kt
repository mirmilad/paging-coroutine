package androidx.paging

import androidx.arch.core.util.Function

internal class CoroutineWrapperPageKeyedDataSource<K, A, B>(
    private val mSource: CoroutinePageKeyedDataSource<K, A>,
    val mListFunction: Function<List<A>, List<B>>/* synthetic access */
) : CoroutinePageKeyedDataSource<K, B>() {

    override fun addInvalidatedCallback(onInvalidatedCallback: DataSource.InvalidatedCallback) {
        mSource.addInvalidatedCallback(onInvalidatedCallback)
    }

    override fun removeInvalidatedCallback(onInvalidatedCallback: DataSource.InvalidatedCallback) {
        mSource.removeInvalidatedCallback(onInvalidatedCallback)
    }

    override fun invalidate() {
        mSource.invalidate()
    }

    override fun isInvalid(): Boolean {
        return mSource.isInvalid
    }

    override suspend fun loadInitial(params: LoadInitialParams<K>): InitialResult<K, B> {
        val result = mSource.loadInitial(params)
        return result.run {
            val convert = DataSource.convert(mListFunction, data)
            if(position == 0 && totalCount == 0)
                InitialResult<K, B>(convert, previousPageKey, nextPageKey)
            else
                InitialResult<K, B>(convert, position, totalCount, previousPageKey, nextPageKey)
        }
    }


    override suspend fun loadBefore(params: CoroutinePageKeyedDataSource.LoadParams<K>): LoadResult<K, B> {
        var result = mSource.loadBefore(params)
        return result.run {
            LoadResult(DataSource.convert(mListFunction, data), adjacentPageKey)
        }
    }

    override suspend fun loadAfter(params: CoroutinePageKeyedDataSource.LoadParams<K>) : LoadResult<K, B> {
        var result = mSource.loadAfter(params)
        return result.run {
            return LoadResult(
                DataSource.convert(mListFunction, data),
                adjacentPageKey
            )
        }
    }
}