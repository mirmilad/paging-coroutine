## Paging-Coroutine
A paging library which uses Kotlin Coroutine instead of background executor and callback, based on [androidx.paging](https://developer.android.com/topic/libraries/architecture/paging) (Ver: 2.1.0) architucture component.

## Usage
### How to Use
The paging-coroutine library behaves like the original paging library. But there are few modifications on classes name and abstract methods. When you extend a data source, instead of using callbacks to pass data to library, you have to return it.

The following instructions and samples shows how to use paging-coroutine library.

#### DataSource Implementation
There are different data source classes exactly like the original library data source classes which named with **Coroutine** prefix.

All of the suspend methods in data source class run on [Default dispatcher](https://developer.android.com/kotlin/coroutines). If you need to use another dispatcher, you can use `coroutineScope` property of data source.

For example, extend your data source class from [**CoroutinePageKeyedDataSource**][1] class and implement the required methods like below:
```kotlin
class MyDataSource(api: ApiInterface)
				: CoroutinePageKeyedDataSource<Int, MyModel>()
{
	override suspend fun loadInitial(params: LoadInitialParams<Int>)
				: InitialResult<Int, MovieModel> {
		try {
			//sample using of a suspend method
			val result = api.suspendGetList(...)	//blocks current Coroutine till response comes
			return InitialResult.Success(result)
		} catch (e: Exception) {
			//you should notify user
			return InitialResult.Error(e)
		}
	}
	
	override suspend fun loadBefore(params: LoadParams<Int>)
				:CoroutinePageKeyedDataSource.LoadResult<Int, MyModel> {
		//No Data
		return InitialResult.None
	}
	
	override suspend fun loadAfter(params: LoadParams<Int>)
				: CoroutinePageKeyedDataSource.LoadResult<Int, MyModel> {
		//sample of using none suspend method in IO dispatcher
		lateinit var retVal: LoadResult<Int, MyModel>;
		this.coroutineScope.launch(Dispatchers.IO) {
			try {
				val result = api.getList(...).execute()
				retVal = LoadResult.Success(result)
			} catch (e: Exception) {
				//you should notify user
				retVal = LoadResult.Error(e)
			}
		}.join()
		return retVal
	}
}
```
#### DataSource.Factory Implementation
Extend your data source factory class from [**CoroutineDataSource.Factory**][2] class and implement `create` method to return a data source.
```kotlin
class MyDataSourceFactory(private val api: ApiInterface)
				: CoroutineDataSource.Factory<Int, MyModel>() {
	val sourceLiveData = MutableLiveData<MyDataSource>()
	override fun create(): DataSource<Int, MyModel> {
		val source = MyDataSource(api)
		sourceLiveData.postValue(source)
		return source
	}
}
```
#### LiveData
In order to configure and creating a LiveData, you have to call `CoroutineDataSource.Factory.toCoroutineLiveData` method. The following code snippet shows how to use this method.
```kotlin
val dataSourceFactory = MyDataSourceFactory(api)
val liveData = dataSourceFactory.toCoroutineLiveData(pageSize = 10)
```

## Lifecycle handling
When you don\'t need resources and data any more you should release them. By calling `CoroutineDataSource.cancelCouroutines()` method, all runing coroutines related to this data source will cancel immediately. 

If you are using ViewModel you have to call this method inside of `onCleared`. The following code snippet is an example that shows how to use this method.
```kotlin
class MyViewModel() : ViewModel() {
	private val dataSourceFactory = MyDataSourceFactory(api)
	val liveData = dataSourceFactory.toCoroutineLiveData(pageSize = 10)

	override fun onCleared() {
		super.onCleared()
		dataSourceFactory.sourceLiveData?.value?.cancelCoroutines()
	}
}
```

## Compatibility
Since [DataSource](https://developer.android.com/reference/androidx/paging/DataSource) and [PagedList](https://developer.android.com/reference/androidx/paging/PagedList) classes are used from origianl library, paging-coroutine library is compatible with [PagedListAdapter](https://developer.android.com/reference/androidx/paging/PagedListAdapter.html). 

Since PagedList class needs an executor as notifyExecutor, paging-coroutine library still uses MainThreadExecutor.

## To Do List
- Writing more tests and coverages
- Removing MainThreadExecutor

[1]: paging/src/main/java/androidx/paging/CoroutinePageKeyedDataSource.kt
[2]: paging/src/main/java/androidx/paging/CoroutineDataSource.kt
