package dev.amaro.broadcastflow

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch


@RunWith(RobolectricTestRunner::class)
class FlowWithBroadcastEventsTest {

    private lateinit var app: Application

    companion object {
        const val ACTION_1 = "SOMETHING_1"
        const val ACTION_2 = "SOMETHING_2"
        const val ACTION_3 = "SOMETHING_3"
        val INTENT_1A = Intent(ACTION_1)
        val INTENT_1B = Intent(ACTION_1).also { it.extras?.putBoolean("TEST", true) }
        val INTENT_2 = Intent(ACTION_2)
        val INTENT_3 = Intent(ACTION_3)
        val EXIT_ON_FIRST = FlowCaster.ExitCondition.Builder(1).build()
        val EXIT_ON_SECOND = FlowCaster.ExitCondition.Builder(2).build()
        val EXIT_ON_ACTION_2 = FlowCaster.ExitCondition.Builder(action = ACTION_2).build()
        val EXIT_ON_ACTION_3 = FlowCaster.ExitCondition.Builder(action = ACTION_3).build()
        val SEND_RESULT_3_COMMAND = Callable<Flow<Intent>> { flowOf(INTENT_3) }
        val SEND_RESULT_ERROR = Callable<Flow<Intent>> { throw Exception() }
    }

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        ShadowLog.setupLogging()
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `Receive single broadcast event as flow emission`() {
        val events = mutableListOf<Intent>()
        val barrier = CountDownLatch(1)
        val setup = FlowCaster.BroadcastSetup(listOf(ACTION_1))
        FlowCaster.listen(app, setup)
            .consume {
                events.add(it)
                barrier.countDown()
            }
        app.sendBroadcast(INTENT_1A)
        barrier.await()
        assertTrue(INTENT_1A.isSame(events[0]))
    }


    @Test
    fun `Receive many broadcast events as flow emission`() {
        val events = mutableListOf<Intent>()
        val barrier = CountDownLatch(1)
        val setup = FlowCaster.BroadcastSetup(listOf(ACTION_1))
        FlowCaster.listen(app, setup)
            .consume {
                events.add(it)
                if (events.count() == 2) barrier.countDown()
            }
        app.sendBroadcast(INTENT_1A)
        app.sendBroadcast(INTENT_1B)
        barrier.await()
        assertTrue(INTENT_1A.isSame(events[0]))
        assertTrue(INTENT_1B.isSame(events[1]))
    }

    @Test
    fun `Receive broadcast events until reach event count`() {
        val events = mutableListOf<Intent>()
        val barrier = CountDownLatch(1)
        val setup = FlowCaster.BroadcastSetup(listOf(ACTION_1), EXIT_ON_FIRST)
        FlowCaster.listen(app, setup)
            .onCompletion { barrier.countDown() }
            .consume { events.add(it) }
        app.sendBroadcast(INTENT_1A)
        app.sendBroadcast(INTENT_1B)
        barrier.await()
        assertEquals(1, events.count())
    }

    @Test
    fun `Receive broadcast events until a given event action`() {
        val events = mutableListOf<Intent>()
        val barrier = CountDownLatch(1)
        val setup = FlowCaster.BroadcastSetup(listOf(ACTION_1, ACTION_2), EXIT_ON_ACTION_2)
        FlowCaster.listen(app, setup)
            .onCompletion { barrier.countDown() }
            .consume { events.add(it) }
        app.sendBroadcast(INTENT_1A)
        app.sendBroadcast(INTENT_2)
        app.sendBroadcast(INTENT_1B)
        barrier.await()
        assertEquals(2, events.count())
        assertTrue(INTENT_2.isSame(events[1]))
    }

    @Test
    fun `Do not receive exit event`() {
        val events = mutableListOf<Intent>()
        val barrier = CountDownLatch(1)
        val setup =
            FlowCaster.BroadcastSetup(listOf(ACTION_1, ACTION_2), EXIT_ON_ACTION_2, false)
        FlowCaster.listen(app, setup)
            .onCompletion { barrier.countDown() }
            .consume { events.add(it) }
        app.sendBroadcast(INTENT_1A)
        app.sendBroadcast(INTENT_2)
        barrier.await()
        assertEquals(1, events.count())
    }

    @Test
    fun `Run action before start listening`() {
        val events = mutableListOf<Intent>()
        val barrier = CountDownLatch(1)
        val setup = FlowCaster.BroadcastSetup(
            listOf(ACTION_1),
            EXIT_ON_SECOND,
            startCommand = SEND_RESULT_3_COMMAND
        )
        FlowCaster.listen(app, setup)
            .onCompletion { barrier.countDown() }
            .consume { events.add(it) }
        app.sendBroadcast(INTENT_1A)
        app.sendBroadcast(INTENT_2)
        barrier.await()
        assertEquals(2, events.count())
        assertEquals(ACTION_3, events[0].action)
    }

    @Test
    fun `Run action before start listening with fail`() {
        val events = mutableListOf<Intent>()
        val barrier = CountDownLatch(1)
        val setup = FlowCaster.BroadcastSetup(
            listOf(ACTION_1),
            EXIT_ON_FIRST,
            startCommand = SEND_RESULT_ERROR
        )
        FlowCaster.listen(app, setup)
            .catch { barrier.countDown() }
            .consume { events.add(it) }
        app.sendBroadcast(INTENT_1A)
        app.sendBroadcast(INTENT_2)
        barrier.await()
        assertEquals(0, events.count())
    }

    @Test
    fun `Initial command returns exit condition`() {
        val events = mutableListOf<Intent>()
        val barrier = CountDownLatch(1)
        val setup = FlowCaster.BroadcastSetup(
            listOf(ACTION_1),
            EXIT_ON_ACTION_3,
            startCommand = SEND_RESULT_3_COMMAND
        )
        FlowCaster.listen(app, setup)
            .onCompletion { barrier.countDown() }
            .consume { events.add(it) }
        app.sendBroadcast(INTENT_1A)
        app.sendBroadcast(INTENT_2)
        barrier.await()
        assertEquals(1, events.count())
        assertEquals(ACTION_3, events[0].action)
    }

    private fun Intent.isSame(other: Intent): Boolean {
        return this.action == other.action && this.`package` == other.`package` && this.extras == other.extras
    }

    private fun <T> Flow<T>.consume(block: (T) -> Unit) {
        GlobalScope.async { this@consume.collect { block(it) } }
    }
}