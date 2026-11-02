package com.wscanplus.android

import android.content.Context
import android.net.wifi.ScanResult
import com.wscanplus.android.core.AttackHeuristics
import com.wscanplus.android.service.ScanCoordinator
import com.wscanplus.android.service.ScanExecutionState
import com.wscanplus.android.service.ScanResultCompat
import com.wscanplus.android.service.WifiScanner
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ScanCoordinatorTest {

    private lateinit var coordinator: ScanCoordinator
    private lateinit var heuristics: AttackHeuristics

    @Before
    fun setup() {
        heuristics = AttackHeuristics()
        coordinator = ScanCoordinator(heuristics = heuristics)
    }

    private class ControllableScanner(
        val scanDelayMs: Long = 50L,
        val produceResults: () -> List<ScanResult> = {
            listOf(
                ScanResultCompat.create("AA:BB:CC:DD:EE:01", "LabNet", -50, 2412),
                ScanResultCompat.create("AA:BB:CC:DD:EE:02", "LabNet", -65, 2462)
            )
        }
    ) : WifiScanner {
        val invocationCount = AtomicInteger(0)
        val activeConcurrentScans = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)

        override suspend fun scan(): List<ScanResult> {
            invocationCount.incrementAndGet()
            val current = activeConcurrentScans.incrementAndGet()
            maxObservedConcurrency.updateAndGet { maxOf(it, current) }
            delay(scanDelayMs)
            activeConcurrentScans.decrementAndGet()
            return produceResults()
        }
    }

    @Test
    fun `rapid repeated taps - coalesces requests with zero overlapping hardware scans`() = runBlocking {
        val testScanner = ControllableScanner(scanDelayMs = 100L)

        // Launch 5 rapid taps concurrently
        val jobs = (1..5).map {
            async {
                coordinator.requestInstantScan(null, testScanner)
            }
        }

        val results = jobs.awaitAll()

        // Verify that all 5 callers received successful results
        assertEquals(5, results.size)
        results.forEach {
            assertTrue(it.success)
            assertEquals(2, it.apsFound)
        }

        // Concurrency must NEVER exceed 1
        assertEquals("Hardware scan must never run concurrently", 1, testScanner.maxObservedConcurrency.get())
        // Invocations should be heavily coalesced rather than running 5 times
        assertTrue("Requests should coalesce into 1 or 2 cycles instead of 5", testScanner.invocationCount.get() <= 2)
    }

    @Test
    fun `simultaneous requests - continuous monitoring and instant scan do not overlap`() = runBlocking {
        val testScanner = ControllableScanner(scanDelayMs = 80L)

        coordinator.setContinuousMonitoringActive(true)
        assertTrue(coordinator.scanState.value.isContinuousActive)

        // Trigger continuous scan and instant scan simultaneously
        val continuousJob = async {
            coordinator.executeContinuousScan(null, testScanner)
        }
        val instantJob = async {
            coordinator.requestInstantScan(null, testScanner)
        }

        val contResult = continuousJob.await()
        val instantResult = instantJob.await()

        assertTrue(contResult.success)
        assertTrue(instantResult.success)

        // Concurrency across both triggers must be strictly 1
        assertEquals("Concurrent hardware scan count must never exceed 1", 1, testScanner.maxObservedConcurrency.get())
        assertEquals(ScanExecutionState.COMPLETED, coordinator.scanState.value.executionState)
        assertEquals(2, coordinator.scanState.value.lastScanResultCount)
    }

    @Test
    fun `scan cancellation - releases lock and resets state cleanly`() = runBlocking {
        val testScanner = ControllableScanner(scanDelayMs = 500L)

        val job = launch {
            coordinator.requestInstantScan(null, testScanner)
        }

        // Wait until scan starts running
        delay(50L)
        assertEquals(ScanExecutionState.RUNNING, coordinator.scanState.value.executionState)

        // Cancel job mid-flight
        job.cancelAndJoin()

        // Lock must be released so next scan executes without deadlock
        val followUpScanner = ControllableScanner(scanDelayMs = 10L)
        val followUpResult = coordinator.requestInstantScan(null, followUpScanner)

        assertTrue("Follow-up scan must succeed after cancellation", followUpResult.success)
        assertEquals(ScanExecutionState.COMPLETED, coordinator.scanState.value.executionState)
    }

    @Test
    fun `backend failure - transitions to FAILED state and updates error telemetry`() = runBlocking {
        val failingScanner = object : WifiScanner {
            override suspend fun scan(): List<ScanResult> {
                throw IOException("Hardware device wlan1 I/O timeout")
            }
        }

        val result = coordinator.requestInstantScan(null, failingScanner)

        assertFalse(result.success)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Hardware device wlan1 I/O timeout"))

        assertEquals(ScanExecutionState.FAILED, coordinator.scanState.value.executionState)
        assertEquals(1, coordinator.scanState.value.consecutiveFailures)
        assertTrue(coordinator.scanState.value.statusMessage.contains("Scan failed"))
    }

    @Test
    fun `state synchronization - reflects continuous active and completed stats`() = runBlocking {
        val testScanner = ControllableScanner(scanDelayMs = 20L)

        coordinator.setContinuousMonitoringActive(true)
        assertTrue(coordinator.scanState.value.isContinuousActive)

        coordinator.executeContinuousScan(null, testScanner)

        val state = coordinator.scanState.value
        assertEquals(ScanExecutionState.COMPLETED, state.executionState)
        assertEquals(2, state.lastScanResultCount)
        assertTrue(state.isContinuousActive)
        assertFalse(state.isInstantScanActive)

        coordinator.setContinuousMonitoringActive(false)
        assertFalse(coordinator.scanState.value.isContinuousActive)
    }
}
