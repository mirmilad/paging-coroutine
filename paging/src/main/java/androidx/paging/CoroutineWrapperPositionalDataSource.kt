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

internal class CoroutineWrapperPositionalDataSource<A, B>(
    private val mSource: CoroutinePositionalDataSource<A>,
    /* synthetic access */val mListFunction: Function<List<A>, List<B>>
) : CoroutinePositionalDataSource<B>() {
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

    override fun loadInitial(
        params: LoadInitialParams,
        callback: LoadInitialCallback<B>
    ) {
        mSource.loadInitial(
            params,
            object : LoadInitialCallback<A>() {
                override fun onResult(
                    data: List<A>,
                    position: Int,
                    totalCount: Int
                ) {
                    callback.onResult(
                        convert(mListFunction, data),
                        position,
                        totalCount
                    )
                }

                override fun onResult(data: List<A>, position: Int) {
                    callback.onResult(
                        convert(mListFunction, data),
                        position
                    )
                }
            })
    }

    override fun loadRange(
        params: LoadRangeParams,
        callback: LoadRangeCallback<B>
    ) {
        mSource.loadRange(params, object : LoadRangeCallback<A>() {
            override fun onResult(data: List<A>) {
                callback.onResult(convert(mListFunction, data))
            }
        })
    }

}