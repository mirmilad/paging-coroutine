package androidx.paging

import kotlinx.coroutines.CoroutineScope

abstract class CoroutineContiguousDataSource<Key, Value> : CoroutineDataSource<Key, Value>() {

    override fun isContiguous(): Boolean {
        return true
    }

    internal abstract suspend fun dispatchLoadInitial(
        key: Key?,
        initialLoadSize: Int,
        pageSize: Int,
        enablePlaceholders: Boolean
    ) : CoroutinePageResult<Value>

    internal abstract suspend fun dispatchLoadAfter(
        currentEndIndex: Int,
        currentEndItem: Value,
        pageSize: Int
    ) : CoroutinePageResult<Value>

    internal abstract suspend fun dispatchLoadBefore(
        currentBeginIndex: Int,
        currentBeginItem: Value,
        pageSize: Int
    ) : CoroutinePageResult<Value>

    /**
     * Get the key from either the position, or item, or null if position/item invalid.
     *
     *
     * Position may not match passed item's position - if trying to query the key from a position
     * that isn't yet loaded, a fallback item (last loaded item accessed) will be passed.
     */
    internal abstract fun getKey(position: Int, item: Value?): Key?

    internal open fun supportsPageDropping(): Boolean {
        return true
    }
}