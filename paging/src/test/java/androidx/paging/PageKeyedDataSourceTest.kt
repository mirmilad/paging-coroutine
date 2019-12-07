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

import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.verifyZeroInteractions

@RunWith(JUnit4::class)
class PageKeyedDataSourceTest {

    private val mMainThread = TestExecutor()
    //private val mBackgroundThread = TestExecutor()

    private val mMainTestDispatcher = TestCoroutineDispatcher()
    private val mDefaultTestDispatcher = TestCoroutineDispatcher()
    private val mIOTestDispatcher = TestCoroutineDispatcher()

    @Before
    fun setup() {
        // provide the scope explicitly, in this example using a constructor parameter
        Dispatchers.setMain(mMainTestDispatcher)

        mockkStatic(Dispatchers::class)
        every {
            Dispatchers.Default
        } returns mDefaultTestDispatcher
        every {
            Dispatchers.IO
        } returns mIOTestDispatcher
    }

    @After
    fun cleanUp() {
        Dispatchers.resetMain()
        mMainTestDispatcher.cleanupTestCoroutines()
        mDefaultTestDispatcher.cleanupTestCoroutines()
        mIOTestDispatcher.cleanupTestCoroutines()
    }

    internal data class Item(val name: String)

    internal data class Page(val prev: String?, val data: List<Item>, val next: String?)

    internal class ItemDataSource(val data: Map<String, Page> = PAGE_MAP)
        : CoroutinePageKeyedDataSource<String, Item>() {

        override suspend fun loadInitial(params: LoadInitialParams<String>): InitialResult<String, Item> {
            val page = getPage(INIT_KEY)
            return InitialResult(page.data, page.prev, page.next)
        }

        override suspend fun loadBefore(params: LoadParams<String>): LoadResult<String, Item> {
            val page = getPage(params.key)
            return LoadResult(page.data, page.prev)
        }

        override suspend fun loadAfter(params: LoadParams<String>): LoadResult<String, Item> {
            val page = getPage(params.key)
            return LoadResult(page.data, page.next)
        }

        private fun getPage(key: String): Page = data[key]!!
    }

    @Test
    fun loadFullVerify() {
        // validate paging entire ItemDataSource results in full, correctly ordered data
        val pagedList = CoroutineContiguousPagedList<String, Item>(ItemDataSource(),
            mMainThread,
            null, PagedList.Config.Builder().setPageSize(100).build(), null,
            ContiguousPagedList.LAST_LOAD_UNSPECIFIED)

        // validate initial load
        assertEquals(PAGE_MAP[INIT_KEY]!!.data, pagedList)

        // flush the remaining loads
        for (i in 0..PAGE_MAP.keys.size) {
            pagedList.loadAround(0)
            pagedList.loadAround(pagedList.size - 1)
            drain()
        }

        // validate full load
        assertEquals(ITEM_LIST, pagedList)
    }

    private fun performLoadInitial(
        invalidateDataSource: Boolean = false,
        callbackInvoker:
            (callback: PageKeyedDataSource.LoadInitialCallback<String, String>) -> Unit
    ) {
        val dataSource = object : PageKeyedDataSource<String, String>() {
            override fun loadInitial(
                params: LoadInitialParams<String>,
                callback: LoadInitialCallback<String, String>
            ) {
                if (invalidateDataSource) {
                    // invalidate data source so it's invalid when onResult() called
                    invalidate()
                }
                callbackInvoker(callback)
            }

            override fun loadBefore(
                params: LoadParams<String>,
                callback: LoadCallback<String, String>
            ) {
                fail("loadBefore not expected")
            }

            override fun loadAfter(
                params: LoadParams<String>,
                callback: LoadCallback<String, String>
            ) {
                fail("loadAfter not expected")
            }
        }

        ContiguousPagedList<String, String>(
            dataSource, FailExecutor(), FailExecutor(), null,
            PagedList.Config.Builder()
                .setPageSize(10)
                .build(),
            "",
            ContiguousPagedList.LAST_LOAD_UNSPECIFIED)
    }

    @Test
    fun loadInitialCallbackSuccess() = performLoadInitial {
        // LoadInitialCallback correct usage
        it.onResult(listOf("a", "b"), 0, 2, null, null)
    }

    @Test
    fun loadInitialCallbackNotPageSizeMultiple() = performLoadInitial {
        // Keyed LoadInitialCallback *can* accept result that's not a multiple of page size
        val elevenLetterList = List(11) { "" + 'a' + it }
        it.onResult(elevenLetterList, 0, 12, null, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun loadInitialCallbackListTooBig() = performLoadInitial {
        // LoadInitialCallback can't accept pos + list > totalCount
        it.onResult(listOf("a", "b", "c"), 0, 2, null, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun loadInitialCallbackPositionTooLarge() = performLoadInitial {
        // LoadInitialCallback can't accept pos + list > totalCount
        it.onResult(listOf("a", "b"), 1, 2, null, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun loadInitialCallbackPositionNegative() = performLoadInitial {
        // LoadInitialCallback can't accept negative position
        it.onResult(listOf("a", "b", "c"), -1, 2, null, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun loadInitialCallbackEmptyCannotHavePlaceholders() = performLoadInitial {
        // LoadInitialCallback can't accept empty result unless data set is empty
        it.onResult(emptyList(), 0, 2, null, null)
    }

    @Test
    fun initialLoadCallbackInvalidThreeArg() = performLoadInitial(invalidateDataSource = true) {
        // LoadInitialCallback doesn't throw on invalid args if DataSource is invalid
        it.onResult(emptyList(), 0, 1, null, null)
    }

    @Test
    fun pageDroppingNotSupported() {
        assertFalse(ItemDataSource().supportsPageDropping())
    }

    fun testBoundaryCallback() {
        val dataSource = object : PageKeyedDataSource<String, String>() {
            override fun loadInitial(
                params: LoadInitialParams<String>,
                callback: LoadInitialCallback<String, String>
            ) {
                callback.onResult(listOf("B"), "a", "c")
            }

            override fun loadBefore(
                params: LoadParams<String>,
                callback: LoadCallback<String, String>
            ) {
                assertEquals("a", params.key)
                callback.onResult(listOf("A"), null)
            }

            override fun loadAfter(
                params: LoadParams<String>,
                callback: LoadCallback<String, String>
            ) {
                assertEquals("c", params.key)
                callback.onResult(listOf("C"), null)
            }
        }

        @Suppress("UNCHECKED_CAST")
        val boundaryCallback =
            mock(PagedList.BoundaryCallback::class.java) as PagedList.BoundaryCallback<String>
        val executor = TestExecutor()
        val pagedList = ContiguousPagedList<String, String>(
            dataSource,
            executor,
            executor,
            boundaryCallback,
            PagedList.Config.Builder()
                .setPageSize(10)
                .build(),
            "",
            ContiguousPagedList.LAST_LOAD_UNSPECIFIED)
        pagedList.loadAround(0)

        verifyZeroInteractions(boundaryCallback)

        executor.executeAll()

        // verify boundary callbacks are triggered
        verify(boundaryCallback).onItemAtFrontLoaded("A")
        verify(boundaryCallback).onItemAtEndLoaded("C")
        verifyNoMoreInteractions(boundaryCallback)
    }

    @Test
    fun testBoundaryCallbackJustInitial() {
        val dataSource = object : PageKeyedDataSource<String, String>() {
            override fun loadInitial(
                params: LoadInitialParams<String>,
                callback: LoadInitialCallback<String, String>
            ) {
                // just the one load, but boundary callbacks should still be triggered
                callback.onResult(listOf("B"), null, null)
            }

            override fun loadBefore(
                params: LoadParams<String>,
                callback: LoadCallback<String, String>
            ) {
                fail("loadBefore not expected")
            }

            override fun loadAfter(
                params: LoadParams<String>,
                callback: LoadCallback<String, String>
            ) {
                fail("loadBefore not expected")
            }
        }

        @Suppress("UNCHECKED_CAST")
        val boundaryCallback =
            mock(PagedList.BoundaryCallback::class.java) as PagedList.BoundaryCallback<String>
        val executor = TestExecutor()
        val pagedList = ContiguousPagedList<String, String>(
            dataSource,
            executor,
            executor,
            boundaryCallback,
            PagedList.Config.Builder()
                .setPageSize(10)
                .build(),
            "",
            ContiguousPagedList.LAST_LOAD_UNSPECIFIED)
        pagedList.loadAround(0)

        verifyZeroInteractions(boundaryCallback)

        executor.executeAll()

        // verify boundary callbacks are triggered
        verify(boundaryCallback).onItemAtFrontLoaded("B")
        verify(boundaryCallback).onItemAtEndLoaded("B")
        verifyNoMoreInteractions(boundaryCallback)
    }

    private abstract class WrapperDataSource<K, A, B>(private val source: CoroutinePageKeyedDataSource<K, A>)
        : CoroutinePageKeyedDataSource<K, B>() {
        override fun addInvalidatedCallback(onInvalidatedCallback: InvalidatedCallback) {
            source.addInvalidatedCallback(onInvalidatedCallback)
        }

        override fun removeInvalidatedCallback(onInvalidatedCallback: InvalidatedCallback) {
            source.removeInvalidatedCallback(onInvalidatedCallback)
        }

        override fun invalidate() {
            source.invalidate()
        }

        override fun isInvalid(): Boolean {
            return source.isInvalid
        }

        override suspend fun loadInitial(params: LoadInitialParams<K>): InitialResult<K, B> {
            source.loadInitial(params).run {
                return InitialResult(convert(data), position, totalCount, previousPageKey, nextPageKey)
            }

        }

        override suspend fun loadAfter(params: LoadParams<K>): LoadResult<K, B> {
            source.loadAfter(params).run {
                return LoadResult(convert(data), adjacentPageKey)
            }
        }

        override suspend fun loadBefore(params: LoadParams<K>): LoadResult<K, B> {
            source.loadBefore(params).run {
                return LoadResult(convert(data), adjacentPageKey)
            }
        }

        protected abstract fun convert(source: List<A>): List<B>
    }

    private class StringWrapperDataSource<K, V>(source: CoroutinePageKeyedDataSource<K, V>)
        : WrapperDataSource<K, V, String>(source) {
        override fun convert(source: List<V>): List<String> {
            return source.map { it.toString() }
        }
    }

    private fun verifyWrappedDataSource(
        createWrapper: (CoroutinePageKeyedDataSource<String, Item>) -> CoroutinePageKeyedDataSource<String, String>
    ) = runBlockingTest {
        // verify that it's possible to wrap a PageKeyedDataSource, and add info to its data
        val orig = ItemDataSource(data = PAGE_MAP)
        val wrapper = createWrapper(orig)

        // load initial
        val initialResult = wrapper.loadInitial(CoroutinePageKeyedDataSource.LoadInitialParams<String>(4, true))
        val expectedInitial = PAGE_MAP.get(INIT_KEY)!!
        val expectedInitialResult = CoroutinePageKeyedDataSource.InitialResult(expectedInitial.data.map { it.toString() }, expectedInitial.prev, expectedInitial.next)
        assertEquals(expectedInitialResult, initialResult)

        // load after
        val afterResult = wrapper.loadAfter(CoroutinePageKeyedDataSource.LoadParams(expectedInitial.next!!, 4))
        val expectedAfter = PAGE_MAP.get(expectedInitial.next)!!
        val expectedAfterResult = CoroutinePageKeyedDataSource.LoadResult(expectedAfter.data.map { it.toString() }, expectedAfter.next)
        assertEquals(expectedAfterResult, afterResult)

        // load before
        val beforeResult = wrapper.loadBefore(CoroutinePageKeyedDataSource.LoadParams(expectedAfter.prev!!, 4))
        val expectedBeforeResult = CoroutinePageKeyedDataSource.LoadResult(expectedInitial.data.map { it.toString() }, expectedInitial.prev)
        assertEquals(expectedBeforeResult, beforeResult)

        // verify invalidation
        orig.invalidate()
        assertTrue(wrapper.isInvalid)
    }

    @Test
    fun testManualWrappedDataSource() = verifyWrappedDataSource {
        StringWrapperDataSource(it)
    }

    @Test
    fun testListConverterWrappedDataSource() = verifyWrappedDataSource {
        it.mapByPage { it.map { it.toString() } }
    }

    @Test
    fun testItemConverterWrappedDataSource() = verifyWrappedDataSource {
        it.map { it.toString() }
    }

    @Test
    fun testInvalidateToWrapper() {
        val orig = ItemDataSource()
        val wrapper = orig.map { it.toString() }

        orig.invalidate()
        assertTrue(wrapper.isInvalid)
    }

    @Test
    fun testInvalidateFromWrapper() {
        val orig = ItemDataSource()
        val wrapper = orig.map { it.toString() }

        wrapper.invalidate()
        assertTrue(orig.isInvalid)
    }

    companion object {
        // first load is 2nd page to ensure we test prepend as well as append behavior
        private val INIT_KEY: String = "key 2"
        private val PAGE_MAP: Map<String, Page>
        private val ITEM_LIST: List<Item>

        init {
            val map = HashMap<String, Page>()
            val list = ArrayList<Item>()
            val pageCount = 5
            for (i in 1..pageCount) {
                val data = List(4) { Item("name $i $it") }
                list.addAll(data)

                val key = "key $i"
                val prev = if (i > 1) ("key " + (i - 1)) else null
                val next = if (i < pageCount) ("key " + (i + 1)) else null
                map.put(key, Page(prev, data, next))
            }
            PAGE_MAP = map
            ITEM_LIST = list
        }
    }

    private fun drain() {
        mDefaultTestDispatcher.advanceUntilIdle()
        mIOTestDispatcher.advanceUntilIdle()
        mMainTestDispatcher.advanceUntilIdle()

        var executed: Boolean
        do {
            //executed = mBackgroundThread.executeAll()
            executed = false
            executed = mMainThread.executeAll() || executed
        } while (executed)

        pauseAllDispatchers()
    }

    private fun pauseAllDispatchers() {
        mDefaultTestDispatcher.pauseDispatcher()
        mIOTestDispatcher.pauseDispatcher()
        mMainTestDispatcher.pauseDispatcher()
    }
}
