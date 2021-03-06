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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

@RunWith(JUnit4::class)
class PositionalDataSourceTest {
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


    private fun computeInitialLoadPos(
            requestedStartPosition: Int,
            requestedLoadSize: Int,
            pageSize: Int,
            totalCount: Int): Int {
        val params = CoroutinePositionalDataSource.LoadInitialParams(
                requestedStartPosition, requestedLoadSize, pageSize, true)
        return CoroutinePositionalDataSource.computeInitialLoadPosition(params, totalCount)
    }

    @Test
    fun computeInitialLoadPositionZero() {
        assertEquals(0, computeInitialLoadPos(
                requestedStartPosition = 0,
                requestedLoadSize = 30,
                pageSize = 10,
                totalCount = 100))
    }

    @Test
    fun computeInitialLoadPositionRequestedPositionIncluded() {
        assertEquals(10, computeInitialLoadPos(
                requestedStartPosition = 10,
                requestedLoadSize = 10,
                pageSize = 10,
                totalCount = 100))
    }

    @Test
    fun computeInitialLoadPositionRound() {
        assertEquals(10, computeInitialLoadPos(
                requestedStartPosition = 13,
                requestedLoadSize = 30,
                pageSize = 10,
                totalCount = 100))
    }

    @Test
    fun computeInitialLoadPositionEndAdjusted() {
        assertEquals(70, computeInitialLoadPos(
                requestedStartPosition = 99,
                requestedLoadSize = 30,
                pageSize = 10,
                totalCount = 100))
    }

    @Test
    fun computeInitialLoadPositionEndAdjustedAndAligned() {
        assertEquals(70, computeInitialLoadPos(
                requestedStartPosition = 99,
                requestedLoadSize = 35,
                pageSize = 10,
                totalCount = 100))
    }

    @Test
    fun fullLoadWrappedAsContiguous() {
        // verify that prepend / append work correctly with a PositionalDataSource, made contiguous
        val config = PagedList.Config.Builder()
                .setPageSize(10)
                .setInitialLoadSizeHint(10)
                .setEnablePlaceholders(true)
                .build()
        val dataSource: CoroutinePositionalDataSource<Int> = CoroutineListDataSource((0..99).toList())
        val testExecutor = TestExecutor()
        val pagedList = CoroutineContiguousPagedList(dataSource.wrapAsContiguousWithoutPlaceholders(),
                testExecutor, null, config, 15,
                CoroutineContiguousPagedList.LAST_LOAD_UNSPECIFIED)

        assertEquals((10..19).toList(), pagedList)

        // prepend + append work correctly
        pagedList.loadAround(5)
        testExecutor.executeAll()
        assertEquals((0..29).toList(), pagedList)

        // and load the rest of the data to be sure further appends work
        for (i in (2..9)) {
            pagedList.loadAround(i * 10 - 5)
            testExecutor.executeAll()
            assertEquals((0..i * 10 + 9).toList(), pagedList)
        }
    }

    private fun performLoadInitial(
            enablePlaceholders: Boolean = true,
            invalidateDataSource: Boolean = false,
            callbackInvoker: () -> CoroutinePositionalDataSource.InitialResult<String>
    ) {
        val dataSource = object : CoroutinePositionalDataSource<String>() {
            override suspend fun loadInitial(
                    params: LoadInitialParams) : InitialResult<String> {
                if (invalidateDataSource) {
                    // invalidate data source so it's invalid when onResult() called
                    invalidate()
                }
                return callbackInvoker()
            }

            override suspend fun loadRange(params: LoadRangeParams) : LoadRangeResult<String> {
                fail("loadRange not expected")
                throw IllegalStateException()
            }
        }

        val config = PagedList.Config.Builder()
                .setPageSize(10)
                .setEnablePlaceholders(enablePlaceholders)
                .build()
        if (enablePlaceholders) {
            CoroutineTiledPagedList(dataSource, FailExecutor(), null, config, 0)
        } else {
            CoroutineContiguousPagedList(dataSource.wrapAsContiguousWithoutPlaceholders(),
                    FailExecutor(), null, config, null,
                    CoroutineContiguousPagedList.LAST_LOAD_UNSPECIFIED)
        }
    }

    @Test
    fun initialLoadCallbackSuccess() = performLoadInitial {
        // LoadInitialCallback correct usage
        CoroutinePositionalDataSource.InitialResult.Success(listOf("a", "b"), 0, 2)
    }

    ////These tests are not applicable because expected exceptions are thrown in coroutine scope
//    @Test(expected = IllegalArgumentException::class)
//    fun initialLoadCallbackNotPageSizeMultiple() = performLoadInitial {
//        // Positional LoadInitialCallback can't accept result that's not a multiple of page size
//        val elevenLetterList = List(11) { "" + 'a' + it }
//        CoroutinePositionalDataSource.InitialResult(elevenLetterList, 0, 12)
//    }
//
//    @Test(expected = IllegalArgumentException::class)
//    fun initialLoadCallbackListTooBig() = performLoadInitial {
//        // LoadInitialCallback can't accept pos + list > totalCount
//        CoroutinePositionalDataSource.InitialResult(listOf("a", "b", "c"), 0, 2)
//    }
//
//    @Test(expected = IllegalArgumentException::class)
//    fun initialLoadCallbackPositionTooLarge() = performLoadInitial {
//        // LoadInitialCallback can't accept pos + list > totalCount
//        CoroutinePositionalDataSource.InitialResult(listOf("a", "b"), 1, 2)
//    }
//
//    @Test(expected = IllegalArgumentException::class)
//    fun initialLoadCallbackPositionNegative() = performLoadInitial {
//        // LoadInitialCallback can't accept negative position
//        CoroutinePositionalDataSource.InitialResult(listOf("a", "b", "c"), -1, 2)
//    }
//
//    @Test(expected = IllegalArgumentException::class)
//    fun initialLoadCallbackEmptyCannotHavePlaceholders() = performLoadInitial {
//        // LoadInitialCallback can't accept empty result unless data set is empty
//        CoroutinePositionalDataSource.InitialResult(emptyList(), 0, 2)
//    }
//
//    @Test(expected = IllegalStateException::class)
//    fun initialLoadCallbackRequireTotalCount() = performLoadInitial(enablePlaceholders = true) {
//        // LoadInitialCallback requires 3 args when placeholders enabled
//        CoroutinePositionalDataSource.InitialResult(listOf("a", "b"), 0)
//    }
//
//    @Test
//    fun initialLoadCallbackSuccessTwoArg() = performLoadInitial(enablePlaceholders = false) {
//        // LoadInitialCallback correct 2 arg usage
//        CoroutinePositionalDataSource.InitialResult(listOf("a", "b"), 0)
//    }
//
//    @Test(expected = IllegalArgumentException::class)
//    fun initialLoadCallbackPosNegativeTwoArg() = performLoadInitial(enablePlaceholders = false) {
//        // LoadInitialCallback can't accept negative position
//        CoroutinePositionalDataSource.InitialResult(listOf("a", "b"), -1)
//    }
//
//    @Test(expected = IllegalArgumentException::class)
//    fun initialLoadCallbackEmptyWithOffset() = performLoadInitial(enablePlaceholders = false) {
//        // LoadInitialCallback can't accept empty result unless pos is 0
//        CoroutinePositionalDataSource.InitialResult(emptyList(), 1)
//    }
//
//    @Test
//    fun initialLoadCallbackInvalidTwoArg() = performLoadInitial(invalidateDataSource = true) {
//        // LoadInitialCallback doesn't throw on invalid args if DataSource is invalid
//        CoroutinePositionalDataSource.InitialResult(emptyList(), 1)
//    }
//
//    @Test
//    fun initialLoadCallbackInvalidThreeArg() = performLoadInitial(invalidateDataSource = true) {
//        // LoadInitialCallback doesn't throw on invalid args if DataSource is invalid
//        CoroutinePositionalDataSource.InitialResult(emptyList(), 0, 1)
//    }

    private abstract class WrapperDataSource<in A, B>(private val source: CoroutinePositionalDataSource<A>)
            : CoroutinePositionalDataSource<B>() {
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

        override suspend fun loadInitial(params: LoadInitialParams) : InitialResult<B> {
            return source.loadInitial(params).run {
                when(this) {
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

        override suspend fun loadRange(params: LoadRangeParams) : LoadRangeResult<B> {
            return source.loadRange(params).run {
                when(this) {
                    LoadRangeResult.None -> LoadRangeResult.None
                    is LoadRangeResult.Error -> this
                    is LoadRangeResult.Success -> LoadRangeResult.Success(convert(data))
                }
            }
        }

        protected abstract fun convert(source: List<A>): List<B>
    }

    private class StringWrapperDataSource<in A>(source: CoroutinePositionalDataSource<A>)
            : WrapperDataSource<A, String>(source) {
        override fun convert(source: List<A>): List<String> {
            return source.map { it.toString() }
        }
    }

    private fun verifyWrappedDataSource(
            createWrapper: (CoroutinePositionalDataSource<Int>) -> CoroutinePositionalDataSource<String>) {
        val orig = CoroutineListDataSource(listOf(0, 5, 4, 8, 12))
        val wrapper = createWrapper(orig)

        // load initial
        //@Suppress("UNCHECKED_CAST")
        //val loadInitialCallback = mock(CoroutinePositionalDataSource.LoadInitialCallback::class.java)
        //        as CoroutinePositionalDataSource.LoadInitialCallback<String>
        mMainTestDispatcher.runBlockingTest {
            val initial =
                wrapper.loadInitial(CoroutinePositionalDataSource.LoadInitialParams(0, 2, 1, true))
            //verify(loadInitialCallback).onResult(listOf("0", "5"), 0, 5)
            //verifyNoMoreInteractions(loadInitialCallback)
            assertEquals(CoroutinePositionalDataSource.InitialResult.Success(listOf("0", "5"), 0, 5), initial)

            // load range
            //@Suppress("UNCHECKED_CAST")
            //val loadRangeCallback =
            //    mock(CoroutinePositionalDataSource.LoadRangeCallback::class.java)
            //            as CoroutinePositionalDataSource.LoadRangeCallback<String>

            val range = wrapper.loadRange(
                CoroutinePositionalDataSource.LoadRangeParams(2, 3))
            //verify(loadRangeCallback).onResult(listOf("4", "8", "12"))
            //verifyNoMoreInteractions(loadRangeCallback)
            assertEquals(CoroutinePositionalDataSource.LoadRangeResult.Success(listOf("4", "8", "12")), range)
        }
        // check invalidation behavior
        val invalCallback = mock(DataSource.InvalidatedCallback::class.java)
        wrapper.addInvalidatedCallback(invalCallback)
        orig.invalidate()
        verify(invalCallback).onInvalidated()
        verifyNoMoreInteractions(invalCallback)

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
        val orig = CoroutineListDataSource(listOf(0, 1, 2))
        val wrapper = orig.map { it.toString() }

        orig.invalidate()
        assertTrue(wrapper.isInvalid)
    }

    @Test
    fun testInvalidateFromWrapper() {
        val orig = CoroutineListDataSource(listOf(0, 1, 2))
        val wrapper = orig.map { it.toString() }

        wrapper.invalidate()
        assertTrue(orig.isInvalid)
    }

    @Test
    fun testInvalidateToWrapper_contiguous() {
        val orig = CoroutineListDataSource(listOf(0, 1, 2))
        val wrapper = orig.wrapAsContiguousWithoutPlaceholders()

        orig.invalidate()
        assertTrue(wrapper.isInvalid)
    }

    @Test
    fun testInvalidateFromWrapper_contiguous() {
        val orig = CoroutineListDataSource(listOf(0, 1, 2))
        val wrapper = orig.wrapAsContiguousWithoutPlaceholders()

        wrapper.invalidate()
        assertTrue(orig.isInvalid)
    }
}
