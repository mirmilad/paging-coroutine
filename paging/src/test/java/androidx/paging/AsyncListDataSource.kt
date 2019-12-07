/*
 * Copyright 2017 The Android Open Source Project
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

import kotlinx.coroutines.channels.Channel

class AsyncListDataSource<T>(list: List<T>)
    : CoroutinePositionalDataSource<T>() {
    private val workItems: MutableList<() -> Unit> = ArrayList()
    private val listDataSource = CoroutineListDataSource(list)

    private val flushChannel = Channel<Boolean>()

    override suspend fun loadInitial(params: LoadInitialParams) : InitialResult<T> {
        val result = listDataSource.loadInitial(params)
        workItems.add {
            result
        }
        flushChannel.receive()
        return result
    }

    override suspend fun loadRange(params: LoadRangeParams) : LoadRangeResult<T> {
        val result = listDataSource.loadRange(params)
        workItems.add {
            result
        }
        flushChannel.receive()
        return result
    }

    suspend fun flush() {
        //workItems.map { it() }
        flushChannel.send(true)
        workItems.clear()
    }
}
