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

package androidx.paging;

import java.util.concurrent.Executor


internal abstract class ContiguousDataSource<Key, Value> : DataSource<Key, Value>() {


    override fun isContiguous(): Boolean {
        return true;
    }

    abstract fun dispatchLoadInitial(
        key: Key?,
        initialLoadSize: Int,
        pageSize: Int,
        enablePlaceholders: Boolean,
        mainThreadExecutor: Executor,
        receiver: PageResult.Receiver<Value>
    )

    abstract fun dispatchLoadAfter(
        currentEndIndex: Int,
        currentEndItem: Value,
        pageSize: Int,
        mainThreadExecutor: Executor,
        receiver: PageResult.Receiver<Value>
    )

    abstract fun dispatchLoadBefore(
        currentBeginIndex: Int,
        currentBeginItem: Value,
        pageSize: Int,
        mainThreadExecutor: Executor,
        receiver: PageResult.Receiver<Value>
    );

    /**
     * Get the key from either the position, or item, or null if position/item invalid.
     * <p>
     * Position may not match passed item's position - if trying to query the key from a position
     * that isn't yet loaded, a fallback item (last loaded item accessed) will be passed.
     */
    internal abstract fun getKey(position: Int, item: Value?): Key?

    open fun supportsPageDropping() = true
}
