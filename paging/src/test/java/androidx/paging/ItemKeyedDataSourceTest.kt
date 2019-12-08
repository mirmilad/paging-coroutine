/*
 * Copyright (C) 2017 The Android Open Source Project
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock
import java.lang.IllegalStateException

@RunWith(JUnit4::class)
class ItemKeyedDataSourceTest {

    private val mMainTestDispatcher = TestCoroutineDispatcher()
    private val mDefaultTestDispatcher = mMainTestDispatcher
    private val mIOTestDispatcher = mMainTestDispatcher

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


    // ----- STANDARD -----

    private fun loadInitial(dataSource: ItemDataSource, key: Key?, initialLoadSize: Int,
                                    enablePlaceholders: Boolean): PageResult<Item> {
        lateinit var result: PageResult<Item>
        runBlockingTest {
            result = loadInitial_suspend(dataSource, key, initialLoadSize, enablePlaceholders)
        }
        return result
    }

    private suspend fun loadInitial_suspend(dataSource: ItemDataSource, key: Key?, initialLoadSize: Int,
            enablePlaceholders: Boolean): PageResult<Item> {
        //@Suppress("UNCHECKED_CAST")
        //val receiver = mock(PageResult.Receiver::class.java) as PageResult.Receiver<Item>
        //@Suppress("UNCHECKED_CAST")
        //val captor = argumentCaptor<PageResult<Item>>() //ArgumentCaptor.forClass(PageResult::class.java)
        //        as ArgumentCaptor<PageResult<Item>>

        val result = dataSource.dispatchLoadInitial(key, initialLoadSize,
                /* ignored pageSize */ 10, enablePlaceholders)

        //verify(receiver).onPageResult(anyInt(), captor.captureForKotlin())
        //verifyNoMoreInteractions(receiver)
        assertTrue(result is CoroutineDataSource.CoroutinePageResult.Success)
        assertNotNull((result as CoroutineDataSource.CoroutinePageResult.Success).pageResult)
        return result.pageResult
    }

    @Test
    fun loadInitial() {
        val dataSource = ItemDataSource()
        val result = loadInitial(dataSource, dataSource.getKey(ITEMS_BY_NAME_ID[49]), 10, true)

        assertEquals(45, result.leadingNulls)
        assertEquals(ITEMS_BY_NAME_ID.subList(45, 55), result.page)
        assertEquals(45, result.trailingNulls)
    }

    @Test
    fun loadInitial_keyMatchesSingleItem() {
        val dataSource = ItemDataSource(items = ITEMS_BY_NAME_ID.subList(0, 1))

        // this is tricky, since load after and load before with the passed key will fail
        val result = loadInitial(dataSource, dataSource.getKey(ITEMS_BY_NAME_ID[0]), 20, true)

        assertEquals(0, result.leadingNulls)
        assertEquals(ITEMS_BY_NAME_ID.subList(0, 1), result.page)
        assertEquals(0, result.trailingNulls)
    }

    @Test
    fun loadInitial_keyMatchesLastItem() {
        val dataSource = ItemDataSource()

        // tricky, because load after key is empty, so another load before and load after required
        val key = dataSource.getKey(ITEMS_BY_NAME_ID.last())
        val result = loadInitial(dataSource, key, 20, true)

        assertEquals(90, result.leadingNulls)
        assertEquals(ITEMS_BY_NAME_ID.subList(90, 100), result.page)
        assertEquals(0, result.trailingNulls)
    }

    @Test
    fun loadInitial_nullKey() {
        val dataSource = ItemDataSource()

        // dispatchLoadInitial(null, count) == dispatchLoadInitial(count)
        val result = loadInitial(dataSource, null, 10, true)

        assertEquals(0, result.leadingNulls)
        assertEquals(ITEMS_BY_NAME_ID.subList(0, 10), result.page)
        assertEquals(90, result.trailingNulls)
    }

    @Test
    fun loadInitial_keyPastEndOfList() {
        val dataSource = ItemDataSource()

        // if key is past entire data set, should return last items in data set
        val key = Key("fz", 0)
        val result = loadInitial(dataSource, key, 10, true)

        // NOTE: ideally we'd load 10 items here, but it adds complexity and unpredictability to
        // do: load after was empty, so pass full size to load before, since this can incur larger
        // loads than requested (see keyMatchesLastItem test)
        assertEquals(95, result.leadingNulls)
        assertEquals(ITEMS_BY_NAME_ID.subList(95, 100), result.page)
        assertEquals(0, result.trailingNulls)
    }

    // ----- UNCOUNTED -----

    @Test
    fun loadInitial_disablePlaceholders() {
        val dataSource = ItemDataSource()

        // dispatchLoadInitial(key, count) == null padding, loadAfter(key, count), null padding
        val key = dataSource.getKey(ITEMS_BY_NAME_ID[49])
        val result = loadInitial(dataSource, key, 10, false)

        assertEquals(0, result.leadingNulls)
        assertEquals(ITEMS_BY_NAME_ID.subList(45, 55), result.page)
        assertEquals(0, result.trailingNulls)
    }

    @Test
    fun loadInitial_uncounted() {
        val dataSource = ItemDataSource(counted = false)

        // dispatchLoadInitial(key, count) == null padding, loadAfter(key, count), null padding
        val key = dataSource.getKey(ITEMS_BY_NAME_ID[49])
        val result = loadInitial(dataSource, key, 10, true)

        assertEquals(0, result.leadingNulls)
        assertEquals(ITEMS_BY_NAME_ID.subList(45, 55), result.page)
        assertEquals(0, result.trailingNulls)
    }

    @Test
    fun loadInitial_nullKey_uncounted() {
        val dataSource = ItemDataSource(counted = false)

        // dispatchLoadInitial(null, count) == dispatchLoadInitial(count)
        val result = loadInitial(dataSource, null, 10, true)

        assertEquals(0, result.leadingNulls)
        assertEquals(ITEMS_BY_NAME_ID.subList(0, 10), result.page)
        assertEquals(0, result.trailingNulls)
    }

    // ----- EMPTY -----

    @Test
    fun loadInitial_empty() {
        val dataSource = ItemDataSource(items = ArrayList())

        // dispatchLoadInitial(key, count) == null padding, loadAfter(key, count), null padding
        val key = dataSource.getKey(ITEMS_BY_NAME_ID[49])
        val result = loadInitial(dataSource, key, 10, true)

        assertEquals(0, result.leadingNulls)
        assertTrue(result.page.isEmpty())
        assertEquals(0, result.trailingNulls)
    }

    @Test
    fun loadInitial_nullKey_empty() {
        val dataSource = ItemDataSource(items = ArrayList())
        val result = loadInitial(dataSource, null, 10, true)

        assertEquals(0, result.leadingNulls)
        assertTrue(result.page.isEmpty())
        assertEquals(0, result.trailingNulls)
    }

    // ----- Other behavior -----

    @Test
    fun loadBefore() = runBlockingTest {
        val dataSource = ItemDataSource()
        @Suppress("UNCHECKED_CAST")
        val callback = mock(CoroutineItemKeyedDataSource.LoadCallback::class.java)
                as CoroutineItemKeyedDataSource.LoadCallback<Item>

        val result = dataSource.loadBefore(
                CoroutineItemKeyedDataSource.LoadParams(dataSource.getKey(ITEMS_BY_NAME_ID[5]), 5))

        //@Suppress("UNCHECKED_CAST")
        //val argument = argumentCaptor<List<Item>>() //ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<Item>>
        //verify(callback).onResult(argument.captureForKotlin())
        //verifyNoMoreInteractions(callback)

        //val observed = argument.value
        assertTrue(result is CoroutineItemKeyedDataSource.LoadResult.Success)
        var observed = (result as CoroutineItemKeyedDataSource.LoadResult.Success).data

        assertEquals(ITEMS_BY_NAME_ID.subList(0, 5), observed)
    }

    internal data class Key(val name: String, val id: Int)

    internal data class Item(
            val name: String, val id: Int, val balance: Double, val address: String)

    internal class ItemDataSource(private val counted: Boolean = true,
                                  private val items: List<Item> = ITEMS_BY_NAME_ID)
            : CoroutineItemKeyedDataSource<Key, Item>() {

        override suspend fun loadInitial(
                params: LoadInitialParams<Key>) : InitialResult<Item> {
            val key = params.requestedInitialKey ?: Key("", Integer.MAX_VALUE)
            val start = Math.max(0, findFirstIndexAfter(key) - params.requestedLoadSize / 2)
            val endExclusive = Math.min(start + params.requestedLoadSize, items.size)

            return if (params.placeholdersEnabled && counted) {
                InitialResult.Success(items.subList(start, endExclusive), start, items.size)
            } else {
                InitialResult.Success(items.subList(start, endExclusive))
            }
        }

        override suspend fun loadAfter(params: LoadParams<Key>) : LoadResult<Item> {
            val start = findFirstIndexAfter(params.key)
            val endExclusive = Math.min(start + params.requestedLoadSize, items.size)

            return LoadResult.Success(items.subList(start, endExclusive))
        }

        override suspend fun loadBefore(params: LoadParams<Key>) : LoadResult<Item> {
            val firstIndexBefore = findFirstIndexBefore(params.key)
            val endExclusive = Math.max(0, firstIndexBefore + 1)
            val start = Math.max(0, firstIndexBefore - params.requestedLoadSize + 1)

            return LoadResult.Success(items.subList(start, endExclusive))
        }

        override fun getKey(item: Item): Key {
            return Key(item.name, item.id)
        }

        private fun findFirstIndexAfter(key: Key): Int {
            return items.indices.firstOrNull {
                KEY_COMPARATOR.compare(key, getKey(items[it])) < 0
            } ?: items.size
        }

        private fun findFirstIndexBefore(key: Key): Int {
            return items.indices.reversed().firstOrNull {
                KEY_COMPARATOR.compare(key, getKey(items[it])) > 0
            } ?: -1
        }
    }

    private fun performLoadInitial(
            invalidateDataSource: Boolean = false,
            callbackInvoker: () -> CoroutineItemKeyedDataSource.InitialResult<String>
    ) {
        val dataSource = object : CoroutineItemKeyedDataSource<String, String>() {
            override fun getKey(item: String): String {
                return ""
            }

            override suspend fun loadInitial(
                    params: LoadInitialParams<String>) : InitialResult<String> {
                if (invalidateDataSource) {
                    // invalidate data source so it's invalid when onResult() called
                    invalidate()
                }
                return callbackInvoker()
            }

            override suspend fun loadAfter(params: LoadParams<String>) : LoadResult<String> {
                fail("loadAfter not expected")
                throw IllegalStateException("loadAfter not expected")
            }

            override suspend fun loadBefore(params: LoadParams<String>) : LoadResult<String> {
                fail("loadBefore not expected")
                throw IllegalStateException("loadAfter not expected")
            }
        }

        CoroutineContiguousPagedList<String, String>(
                dataSource, FailExecutor(), null,
                PagedList.Config.Builder()
                        .setPageSize(10)
                        .build(),
                "",
                CoroutineContiguousPagedList.LAST_LOAD_UNSPECIFIED)
    }

    @Test
    fun loadInitialCallbackSuccess() = performLoadInitial {
        // LoadInitialCallback correct usage
        //CoroutineItemKeyedDataSource.InitialResult(listOf("a", "b"), 0, 2)
        CoroutineItemKeyedDataSource.InitialResult.Success(listOf("a", "b"), 0, 2)
    }

    @Test
    fun loadInitialCallbackNotPageSizeMultiple() = performLoadInitial {
        // Keyed LoadInitialCallback *can* accept result that's not a multiple of page size
        val elevenLetterList = List(11) { "" + 'a' + it }
        CoroutineItemKeyedDataSource.InitialResult.Success(elevenLetterList, 0, 12)
    }

    ////These tests are not applicable because expected exceptions are thrown in coroutine scope
//    @Test(expected = IllegalArgumentException::class)
//    fun loadInitialCallbackListTooBig() = performLoadInitial {
//        // LoadInitialCallback can't accept pos + list > totalCount
//        CoroutineItemKeyedDataSource.InitialResult(listOf("a", "b", "c"), 0, 2)
//    }
//
//    @Test(expected = IllegalArgumentException::class)
//    fun loadInitialCallbackPositionTooLarge() = performLoadInitial {
//        // LoadInitialCallback can't accept pos + list > totalCount
//        CoroutineItemKeyedDataSource.InitialResult(listOf("a", "b"), 1, 2)
//    }
//
//    @Test(expected = IllegalArgumentException::class)
//    fun loadInitialCallbackPositionNegative() = performLoadInitial {
//        // LoadInitialCallback can't accept negative position
//        CoroutineItemKeyedDataSource.InitialResult(listOf("a", "b", "c"), -1, 2)
//    }
//
//    @Test(expected = IllegalArgumentException::class)
//    fun loadInitialCallbackEmptyCannotHavePlaceholders() = performLoadInitial {
//        // LoadInitialCallback can't accept empty result unless data set is empty
//        CoroutineItemKeyedDataSource.InitialResult(emptyList(), 0, 2)
//    }

    @Test
    fun initialLoadCallbackInvalidThreeArg() = performLoadInitial(invalidateDataSource = true) {
        // LoadInitialCallback doesn't throw on invalid args if DataSource is invalid
        CoroutineItemKeyedDataSource.InitialResult.Success(emptyList(), 0, 1)
    }

    private abstract class WrapperDataSource<K, A, B>(private val source: CoroutineItemKeyedDataSource<K, A>)
            : CoroutineItemKeyedDataSource<K, B>() {
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

        override suspend fun loadInitial(params: LoadInitialParams<K>) : InitialResult<B> {
            return source.loadInitial(params).run {
                when (this) {
                    InitialResult.None -> InitialResult.None
                    is InitialResult.Error -> this
                    is InitialResult.Success -> InitialResult.Success(
                        convert(data),
                        position,
                        totalCount
                    )
                }
            }
        }

        override suspend fun loadAfter(params: LoadParams<K>) : LoadResult<B> {
            return source.loadAfter(params).run {
                when(this) {
                    LoadResult.None -> LoadResult.None
                    is LoadResult.Error -> this
                    is LoadResult.Success -> LoadResult.Success(convert(data))
                }
            }
        }

        override suspend fun loadBefore(params: LoadParams<K>) : LoadResult<B> {
            return source.loadBefore(params).run {
                when(this) {
                    LoadResult.None -> LoadResult.None
                    is LoadResult.Error -> this
                    is LoadResult.Success -> LoadResult.Success(convert(data))
                }
            }
        }

        protected abstract fun convert(source: List<A>): List<B>
    }

    private data class DecoratedItem(val item: Item)

    private class DecoratedWrapperDataSource(private val source: CoroutineItemKeyedDataSource<Key, Item>)
            : WrapperDataSource<Key, Item, DecoratedItem>(source) {
        override fun convert(source: List<Item>): List<DecoratedItem> {
            return source.map { DecoratedItem(it) }
        }

        override fun getKey(item: DecoratedItem): Key {
            return source.getKey(item.item)
        }
    }

    private fun verifyWrappedDataSource(createWrapper:
            (CoroutineItemKeyedDataSource<Key, Item>) -> CoroutineItemKeyedDataSource<Key, DecoratedItem>) {
        // verify that it's possible to wrap an ItemKeyedDataSource, and add info to its data

        val orig = ItemDataSource(items = ITEMS_BY_NAME_ID)
        val wrapper = createWrapper(orig)

        // load initial
        //@Suppress("UNCHECKED_CAST")
        //val loadInitialCallback = mock(CoroutineItemKeyedDataSource.LoadInitialCallback::class.java)
        //        as CoroutineItemKeyedDataSource.LoadInitialCallback<DecoratedItem>
        val initKey = orig.getKey(ITEMS_BY_NAME_ID.first())
        mMainTestDispatcher.runBlockingTest {
            val initalResult = wrapper.loadInitial(
                CoroutineItemKeyedDataSource.LoadInitialParams(
                    initKey,
                    10,
                    false
                )
            )
            //verify(loadInitialCallback).onResult(
            //        ITEMS_BY_NAME_ID.subList(0, 10).map { DecoratedItem(it) })
            //verifyNoMoreInteractions(loadInitialCallback)
            assertTrue(initalResult is CoroutineItemKeyedDataSource.InitialResult.Success)
            assertEquals(
                ITEMS_BY_NAME_ID.subList(0, 10).map { DecoratedItem(it) },
                (initalResult as CoroutineItemKeyedDataSource.InitialResult.Success).data
            )

            //@Suppress("UNCHECKED_CAST")
            //val loadCallback = mock(CoroutineItemKeyedDataSource.LoadCallback::class.java)
            //        as CoroutineItemKeyedDataSource.LoadCallback<DecoratedItem>
            val key = orig.getKey(ITEMS_BY_NAME_ID[20])
            // load after
            val after = wrapper.loadAfter(CoroutineItemKeyedDataSource.LoadParams(key, 10))
            //verify(loadCallback).onResult(
            //    ITEMS_BY_NAME_ID.subList(
            //        21,
            //        31
            //    ).map { DecoratedItem(it) })
            //verifyNoMoreInteractions(loadCallback)
            assertTrue(after is CoroutineItemKeyedDataSource.LoadResult.Success)
            assertEquals(ITEMS_BY_NAME_ID.subList(21, 31).map { DecoratedItem(it) },
                (after as CoroutineItemKeyedDataSource.LoadResult.Success).data)

            // load before
            var before = wrapper.loadBefore(CoroutineItemKeyedDataSource.LoadParams(key, 10))
            //verify(loadCallback).onResult(
            //    ITEMS_BY_NAME_ID.subList(
            //        10,
            //        20
            //    ).map { DecoratedItem(it) })
            //verifyNoMoreInteractions(loadCallback)
            assertTrue(before is CoroutineItemKeyedDataSource.LoadResult.Success)
            assertEquals(ITEMS_BY_NAME_ID.subList(10, 20).map { DecoratedItem(it) },
                (before as CoroutineItemKeyedDataSource.LoadResult.Success).data)
        }
        // verify invalidation
        orig.invalidate()
        assertTrue(wrapper.isInvalid)
    }

    @Test
    fun testManualWrappedDataSource() = verifyWrappedDataSource {
        DecoratedWrapperDataSource(it)
    }

    @Test
    fun testListConverterWrappedDataSource() = verifyWrappedDataSource {
        it.mapByPage { it.map { DecoratedItem(it) } }
    }

    @Test
    fun testItemConverterWrappedDataSource() = verifyWrappedDataSource {
        it.map { DecoratedItem(it) }
    }

    @Test
    fun testInvalidateToWrapper() {
        val orig = ItemDataSource()
        val wrapper = orig.map { DecoratedItem(it) }

        orig.invalidate()
        assertTrue(wrapper.isInvalid)
    }

    @Test
    fun testInvalidateFromWrapper() {
        val orig = ItemDataSource()
        val wrapper = orig.map { DecoratedItem(it) }

        wrapper.invalidate()
        assertTrue(orig.isInvalid)
    }

    companion object {
        private val ITEM_COMPARATOR = compareBy<Item>({ it.name }).thenByDescending({ it.id })
        private val KEY_COMPARATOR = compareBy<Key>({ it.name }).thenByDescending({ it.id })

        private val ITEMS_BY_NAME_ID = List(100) {
            val names = Array(10) { "f" + ('a' + it) }
            Item(names[it % 10],
                    it,
                    Math.random() * 1000,
                    (Math.random() * 200).toInt().toString() + " fake st.")
        }.sortedWith(ITEM_COMPARATOR)
    }
}
