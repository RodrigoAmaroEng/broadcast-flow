package dev.amaro.broadcastflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.runBlocking

object FlowCaster {
    fun listen(
        context: Context,
        setup: BroadcastSetup
    ): Flow<Intent> {
        val receiver = FlowReceiver(setup)
        context.registerReceiver(receiver, setup.filter())
        return receiver.listen()
    }


    private class FlowReceiver(private val setup: BroadcastSetup) : BroadcastReceiver() {
        private val channel = Channel<Intent>(UNLIMITED)

        fun listen() = channel.consumeAsFlow()

        private var eventCounter = 0

        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) {
                runBlocking {
                    eventCounter++
                    val shouldExit = setup.exitCondition.shouldExit(intent, eventCounter)
                    if (!shouldExit || setup.emitExitEvent) channel.send(intent)
                    if (shouldExit) dispose(context!!)
                }
            }
        }

        private fun dispose(context: Context) {
            channel.close()
            context.unregisterReceiver(this@FlowReceiver)
        }
    }

    data class BroadcastSetup(
        val actions: List<String>,
        val exitCondition: ExitCondition = ExitCondition.Builder().build(),
        val emitExitEvent: Boolean = true
    ) {
        fun filter() = IntentFilter().also { f -> actions.forEach { f.addAction(it) } }
    }

    interface ExitCondition {
        fun shouldExit(intent: Intent, eventNumber: Int): Boolean
        class Builder(
            private val count: Int = 0,
            private val action: String? = null
        ) {
            fun build(): ExitCondition {
                val rules = mutableListOf<ConditionRule>()
                if (count > 0) rules.add(CountConditionRule(count))
                if (action != null) rules.add(ActionConditionRule(action))
                return object : ExitCondition {
                    override fun shouldExit(intent: Intent, eventNumber: Int): Boolean {
                        return rules.takeIf { it.isNotEmpty() }
                            ?.none { !it.evaluate(intent, eventNumber) } ?: false
                    }
                }
            }

        }
    }

    interface ConditionRule {
        fun evaluate(intent: Intent, eventNumber: Int): Boolean
    }

    class CountConditionRule(private val max: Int) : ConditionRule {
        override fun evaluate(intent: Intent, eventNumber: Int): Boolean {
            return max == eventNumber
        }
    }

    class ActionConditionRule(private val action: String) : ConditionRule {
        override fun evaluate(intent: Intent, eventNumber: Int): Boolean {
            return intent.action == action
        }
    }

}

sealed class Output {
    object Undefined : Output()
    object Yes : Output()
    object No : Output()
    companion object {
        fun from(value: Boolean): Output {
            return if (value) Yes else No
        }
    }
}