package com.fetchclone.feature.receipts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps `Dispatchers.Main` for a test dispatcher around each test.
 *
 * `viewModelScope` runs on `Dispatchers.Main`, which has no implementation in a plain
 * JVM unit test (`Dispatchers.setMain` must be called first). This rule does that in
 * `starting()` and cleans up in `finished()` so tests don't leak the override into
 * each other.
 *
 * It uses [UnconfinedTestDispatcher] so coroutines launched by the ViewModel (here:
 * the `stateIn` sharing coroutine) run **eagerly** — state derivations settle
 * synchronously, so tests can assert without juggling `advanceUntilIdle()`. Use a
 * `StandardTestDispatcher` instead when a test specifically needs to control virtual
 * time / ordering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
