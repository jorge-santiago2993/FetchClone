package com.fetchclone.core.data.di

import com.fetchclone.core.data.network.ConnectivityManagerNetworkMonitor
import com.fetchclone.core.data.network.NetworkMonitor
import com.fetchclone.core.data.receipt.ReceiptProcessor
import com.fetchclone.core.data.receipt.RemoteReceiptProcessor
import com.fetchclone.core.data.receipt.SimulatedProcessorConfig
import com.fetchclone.core.data.receipt.SimulatedReceiptProcessor
import com.fetchclone.core.data.repository.DefaultReceiptRepository
import com.fetchclone.core.data.repository.ReceiptRepository
import com.fetchclone.core.data.work.OutboxSyncScheduler
import com.fetchclone.core.data.work.WorkManagerOutboxSyncScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the receipt submission pipeline.
 *
 * Kept separate from [RepositoryModule] because this module makes a **product decision**
 * that deserves to be findable — which [ReceiptProcessor] the app runs — rather than being
 * one line buried among generic interface-to-implementation bindings.
 *
 * The module is `internal`, as are most of the types it binds. That is deliberate: only
 * [ReceiptRepository] and the domain models are `:core:data`'s public surface. Hilt
 * generates its code into this same Gradle module, so `internal` visibility costs nothing
 * and stops `:feature:receipts` from reaching past the interface it is supposed to depend
 * on. (Contrast `DefaultOffersRepository`, which is public — an earlier, looser call.)
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface ReceiptModule {

    @Binds
    @Singleton
    fun bindReceiptRepository(impl: DefaultReceiptRepository): ReceiptRepository

    @Binds
    @Singleton
    fun bindOutboxSyncScheduler(impl: WorkManagerOutboxSyncScheduler): OutboxSyncScheduler

    @Binds
    @Singleton
    fun bindNetworkMonitor(impl: ConnectivityManagerNetworkMonitor): NetworkMonitor

    /**
     * **The simulated processor is what this app runs. This is the deliberate choice, not
     * an oversight.**
     *
     * Point awarding is a backend decision. How many points a receipt is worth, whether it
     * duplicates one already claimed, whether it is fraudulent — no client may answer
     * those, because a client runs on hardware the user controls. A loyalty app whose
     * client decides its own points has no points.
     *
     * DummyJSON has no concept of any of this. [RemoteReceiptProcessor] is written, is
     * real, and makes the actual `GET /carts/{id}` call — but the response carries no
     * verdict field, so it can only ever return `StillProcessing`. Binding it would leave
     * every receipt parked in `PROCESSING` forever, making `Awarded` and `Rejected`
     * unreachable in the running app and the status badge dead code.
     *
     * So [SimulatedReceiptProcessor] is bound instead. It resolves after a dwell time,
     * awards via `PointsCalculator`, and sends a deterministic slice to `DUPLICATE` so the
     * rejection path is observable at all.
     *
     * **What stays real:** the entire upload path. A live POST to a live API, with a
     * genuine idempotency key, genuine HTTP status classification, genuine retry and
     * backoff. Only the *award decision* is local — and awarding is the one part of this
     * pipeline no client would ever own.
     *
     * Switching to a real backend is this one line. That is the value of the seam, and the
     * reason it would exist even with a real server behind it: no test can wait twenty
     * seconds for a server to award points, so a fake at this exact boundary is needed
     * regardless.
     */
    @Binds
    @Singleton
    fun bindReceiptProcessor(impl: SimulatedReceiptProcessor): ReceiptProcessor

    companion object {

        /**
         * Tuning for the simulator, in one place so a demo can be reshaped without
         * touching the processor.
         *
         * Defaults are used as-is here; the type exists so they are *changeable*. Setting
         * `rejectionRatePercent = 100` is how you go and look at the rejected-receipt
         * badge on a device rather than hoping a receipt id hashes into the 15% slice.
         */
        @Provides
        @Singleton
        fun provideSimulatedProcessorConfig(): SimulatedProcessorConfig =
            SimulatedProcessorConfig()
    }
}
