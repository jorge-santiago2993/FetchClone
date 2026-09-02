package com.fetchclone.core.data.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fetchclone.core.data.database.FetchCloneDatabase
import com.fetchclone.core.data.database.entity.OfferEntity
import com.fetchclone.core.data.network.ProductsApi
import com.fetchclone.core.data.network.model.ProductDto
import com.fetchclone.core.data.network.model.ProductsResponse
import com.fetchclone.core.data.repository.FeedRefreshState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instrumented test for [OffersRemoteMediator] — it needs a real (in-memory) Room
 * database, so it runs on a device/emulator, not the JVM.
 *
 * > Run with: `./gradlew :core:data:connectedDebugAndroidTest`
 *
 * The network side is a hand-written fake [ProductsApi]; no MockWebServer needed. What
 * we are pinning down is the mediator's contract:
 *  - REFRESH writes page 0 to Room and reports [FeedRefreshState.Success];
 *  - `endOfPaginationReached` is computed from `skip + size >= total`;
 *  - a network failure becomes [RemoteMediator.MediatorResult.Error] **without**
 *    wiping the existing cache, and reports [FeedRefreshState.Error].
 */
@OptIn(ExperimentalPagingApi::class)
class OffersRemoteMediatorTest {

    private val database: FetchCloneDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        FetchCloneDatabase::class.java,
    ).build()

    private val refreshState = MutableStateFlow<FeedRefreshState>(FeedRefreshState.Idle)

    @After
    fun closeDb() = database.close()

    @Test
    fun refresh_writesFirstPage_andReportsSuccess() = runTest {
        val mediator = OffersRemoteMediator(FakeProductsApi(total = 50), database, refreshState)

        val result = mediator.load(LoadType.REFRESH, emptyPagingState())

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        // 20 fetched out of 50 -> more pages remain.
        assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(PAGE_SIZE, database.offersDao().observeCount().first())
        assertEquals(FeedRefreshState.Success, refreshState.value)
    }

    @Test
    fun refresh_onLastPage_reportsEndOfPagination() = runTest {
        val mediator = OffersRemoteMediator(FakeProductsApi(total = 12), database, refreshState)

        val result = mediator.load(LoadType.REFRESH, emptyPagingState())

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(12, database.offersDao().observeCount().first())
    }

    @Test
    fun append_afterRefresh_loadsTheSecondPage() = runTest {
        val mediator = OffersRemoteMediator(FakeProductsApi(total = 50), database, refreshState)
        mediator.load(LoadType.REFRESH, emptyPagingState())

        val loaded = database.offersDao().pagingSourceSnapshot()
        val result = mediator.load(LoadType.APPEND, pagingStateEndingWith(loaded))

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertEquals(PAGE_SIZE * 2, database.offersDao().observeCount().first())
    }

    @Test
    fun refresh_networkFailure_keepsCacheAndReportsError() = runTest {
        // Seed a good page first.
        OffersRemoteMediator(FakeProductsApi(total = 50), database, refreshState)
            .load(LoadType.REFRESH, emptyPagingState())
        val cachedBefore = database.offersDao().observeCount().first()

        val result = OffersRemoteMediator(FailingProductsApi, database, refreshState)
            .load(LoadType.REFRESH, emptyPagingState())

        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertTrue(refreshState.value is FeedRefreshState.Error)
        // The cache was NOT cleared — clearAll() only runs after a successful fetch.
        assertEquals(cachedBefore, database.offersDao().observeCount().first())
    }

    // --- helpers -------------------------------------------------------------

    private fun emptyPagingState() = PagingState<Int, OfferEntity>(
        pages = emptyList(),
        anchorPosition = null,
        config = PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE),
        leadingPlaceholderCount = 0,
    )

    private fun pagingStateEndingWith(items: List<OfferEntity>) = PagingState<Int, OfferEntity>(
        pages = listOf(
            androidx.paging.PagingSource.LoadResult.Page<Int, OfferEntity>(
                data = items,
                prevKey = null,
                nextKey = null,
            ),
        ),
        anchorPosition = items.lastIndex,
        config = PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE),
        leadingPlaceholderCount = 0,
    )

    private companion object {
        const val PAGE_SIZE = 20
    }
}

/** Returns products [skip, min(skip+limit, total)) so paging math can be verified. */
private class FakeProductsApi(private val total: Int) : ProductsApi {
    override suspend fun getProducts(limit: Int, skip: Int): ProductsResponse {
        val products = (skip until minOf(skip + limit, total)).map { i ->
            ProductDto(
                id = i,
                title = "Product $i",
                price = 1.0 + i,
                thumbnail = "https://img.example/$i.webp",
            )
        }
        return ProductsResponse(products = products, total = total, skip = skip, limit = limit)
    }
}

private object FailingProductsApi : ProductsApi {
    override suspend fun getProducts(limit: Int, skip: Int): ProductsResponse =
        throw java.io.IOException("simulated offline")
}

/** Small convenience: read every cached row in feed order for building a PagingState. */
private suspend fun com.fetchclone.core.data.database.dao.OffersDao.pagingSourceSnapshot(): List<OfferEntity> {
    val source = pagingSource()
    val page = source.load(
        androidx.paging.PagingSource.LoadParams.Refresh(
            key = null,
            loadSize = 100,
            placeholdersEnabled = false,
        ),
    )
    return (page as androidx.paging.PagingSource.LoadResult.Page).data
}
