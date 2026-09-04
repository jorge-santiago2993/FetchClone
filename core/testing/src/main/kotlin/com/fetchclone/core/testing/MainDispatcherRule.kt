package com.fetchclone.core.testing

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
 * `viewModelScope` runs on `Dispatchers.Main`, which has no implementation in a plain JVM
 * unit test (`Dispatchers.setMain` must be called first). This rule does that in
 * `starting()` and cleans up in `finished()` so tests don't leak the override into each
 * other.
 *
 * It uses [UnconfinedTestDispatcher] so coroutines launched by the ViewModel (typically the
 * `stateIn` sharing coroutine) run **eagerly** — state derivations settle synchronously, so
 * tests can assert without juggling `advanceUntilIdle()`. Use a `StandardTestDispatcher`
 * instead when a test specifically needs to control virtual time or ordering.
 *
 * ## Why this module exists
 *
 * It was duplicated in `:feature:offers` and `:feature:receipts`, and the receipts build
 * tracker recorded the threshold explicitly: *"Two copies is still cheaper than a module; a
 * third would change that."* `:feature:auth` was the third.
 *
 * Worth keeping as a note about when to extract shared code, because the usual failure is
 * at both ends. Extracting at the first duplication produces modules with one class in them
 * and a dependency graph nobody can hold in their head; never extracting produces four
 * copies that quietly drift until one of them has a bug fix the others do not. Writing the
 * threshold down in advance, then honouring it, is what makes the call boring instead of a
 * matter of taste.
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
