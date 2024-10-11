package com.gempukku.context.update

import com.gempukku.context.DefaultGempukkuContext
import com.gempukku.context.lifecycle.LifecycleSystem
import com.gempukku.context.processor.inject.AnnotationSystemInjector
import com.gempukku.context.processor.inject.decorator.WorkerThreadExecutorSystem
import com.gempukku.context.resolver.expose.AnnotationSystemResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PeriodicUpdateTest {
    @Test
    fun testPeriodicUpdate() {
        val lifecycleSystem = LifecycleSystem()
        val periodicallyUpdatedTestSystem = PeriodicallyUpdatedTestSystem()
        val context = DefaultGempukkuContext(
            null, AnnotationSystemResolver(), AnnotationSystemInjector(),
            UpdatingSystem(100), WorkerThreadExecutorSystem(), lifecycleSystem, periodicallyUpdatedTestSystem
        )
        context.initialize()

        lifecycleSystem.start()

        Thread.sleep(2500)
        assertEquals(2, periodicallyUpdatedTestSystem.invocationCount)
    }
}