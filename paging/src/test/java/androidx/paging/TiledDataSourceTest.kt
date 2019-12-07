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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

@Suppress("DEPRECATION")
@RunWith(JUnit4::class)
internal class TiledDataSourceTest {

    private val mMainTestDispatcher = TestCoroutineDispatcher()

    @Before
    fun setup() {
        // provide the scope explicitly, in this example using a constructor parameter
        Dispatchers.setMain(mMainTestDispatcher)
    }

    @After
    fun cleanUp() {
        Dispatchers.resetMain()
        mMainTestDispatcher.cleanupTestCoroutines()
    }

    @Test
    fun loadInitialEmpty() {
        class EmptyDataSource : CoroutineTiledDataSource<String>() {
            override fun countItems(): Int {
                return 0
            }

            override fun loadRange(startPosition: Int, count: Int): List<String> {
                return emptyList()
            }
        }

        assertEquals(emptyList<String>(), EmptyDataSource().loadInitial(0, 1, 5))
    }

    fun CoroutineTiledDataSource<String>.loadInitial(
        startPosition: Int, count: Int, pageSize: Int): List<String> {

        lateinit var observed: PageResult<String>
        //@Suppress("UNCHECKED_CAST")
        //val receiver = mock(PageResult.Receiver::class.java) as PageResult.Receiver<String>
        mMainTestDispatcher.runBlockingTest {
            val initialResult = dispatchLoadInitial(true, startPosition, count, pageSize)

            //@Suppress("UNCHECKED_CAST")
            //val argument = ArgumentCaptor.forClass(PageResult::class.java)
            //        as ArgumentCaptor<PageResult<String>>
            //verify(receiver).onPageResult(eq(PageResult.INIT), argument.capture())
            //verifyNoMoreInteractions(receiver)
            assertEquals(PageResult.INIT, initialResult.type)
            observed = initialResult.pageResult
        }

        return observed.page
    }

    @Test
    fun loadInitialTooLong() {
        val list = List(26) { "" + 'a' + it }
        class AlphabetDataSource : CoroutineTiledDataSource<String>() {
            override fun countItems(): Int {
                return list.size
            }

            override fun loadRange(startPosition: Int, count: Int): List<String> {
                return list.subList(startPosition, startPosition + count)
            }
        }
        // baseline behavior
        assertEquals(list, AlphabetDataSource().loadInitial(0, 26, 10))
        assertEquals(list, AlphabetDataSource().loadInitial(50, 26, 10))
    }
}
