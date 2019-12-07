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
import java.util.*

class CoroutineWrapperItemKeyedDataSource<K, A, B>(
    private val mSource: CoroutineItemKeyedDataSource<K, A>,
    /* synthetic access */val mListFunction: Function<List<A>, List<B>>
) : CoroutineItemKeyedDataSource<K, B>() {
    private val mKeyMap = IdentityHashMap<B, K>()
    override fun addInvalidatedCallback(onInvalidatedCallback: InvalidatedCallback) {
        mSource.addInvalidatedCallback(onInvalidatedCallback)
    }

    override fun removeInvalidatedCallback(onInvalidatedCallback: InvalidatedCallback) {
        mSource.removeInvalidatedCallback(onInvalidatedCallback)
    }

    override fun invalidate() {
        mSource.invalidate()
    }

    override fun isInvalid(): Boolean {
        return mSource.isInvalid
    }

    fun  /* synthetic access */convertWithStashedKeys(source: List<A>): List<B> {
        val dest =
            convert(mListFunction, source)
        synchronized(mKeyMap) {
            // synchronize on mKeyMap, since multiple loads may occur simultaneously.
            // Note: manually sync avoids locking per-item (e.g. Collections.synchronizedMap)
            for (i in dest.indices) {
                mKeyMap[dest[i]] = mSource.getKey(source[i])
            }
        }
        return dest
    }

    override suspend fun loadInitial(params: LoadInitialParams<K>): InitialResult<B> {
        return mSource.loadInitial(params).run {
            InitialResult(convertWithStashedKeys(data), position, totalCount)
        }
    }

    override suspend fun loadAfter(params: LoadParams<K>): LoadResult<B> {
        return mSource.loadAfter(params).run {
            LoadResult(convertWithStashedKeys(data))
        }
    }

    override suspend fun loadBefore(params: LoadParams<K>): LoadResult<B> {
        return mSource.loadBefore(params).run {
            LoadResult(convertWithStashedKeys(data))
        }
    }

    override fun getKey(item: B): K {
        synchronized(mKeyMap) { return mKeyMap[item]!! }
    }

}