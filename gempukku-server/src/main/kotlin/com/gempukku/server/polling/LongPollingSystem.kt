package com.gempukku.server.polling

import com.gempukku.context.resolver.expose.Exposes
import com.gempukku.context.update.UpdatedSystem
import com.gempukku.server.generateUniqueId

@Exposes(LongPolling::class, UpdatedSystem::class)
class LongPollingSystem(private val pollTimeout: Long, private val channelTimeout: Long) : LongPolling, UpdatedSystem {
    private val pollMap: MutableMap<String, PollRegistration> = mutableMapOf()

    override fun <Event> registerLongPoll(eventStream: EventStream<Event>, timeoutRunnable: Runnable?): String {
        var pollId: String
        do {
            pollId = generateUniqueId()
        } while (pollMap.containsKey(pollId))
        pollMap[pollId] = PollRegistration(System.currentTimeMillis(), eventStream, null, timeoutRunnable)
        return pollId
    }

    override fun <Event> registerSink(pollId: String, eventSink: EventSink<Event>): Boolean {
        return pollMap[pollId]?.let {
            it.eventSink?.timedOut()
            it.lastAccessed = System.currentTimeMillis()
            it.eventSink = eventSink
            true
        } ?: false
    }

    override fun update() {
        val updateTime = System.currentTimeMillis()
        // Send awaiting events
        pollMap.forEach {
            val registration = it.value
            registration.eventSink?.let { sink ->
                val events = registration.eventStream.consumeEvents()
                if (events.isNotEmpty()) {
                    sink.processEvents(events as List<Nothing>)
                    registration.eventSink = null
                } else {
                    if (registration.lastAccessed + pollTimeout < updateTime) {
                        sink.timedOut()
                        registration.eventSink = null
                    }
                }
            }
        }
        // Timeout inactive polls
        pollMap.entries.removeAll {
            val remove = it.value.lastAccessed + channelTimeout < updateTime || it.value.eventStream.isFinished()
            if (remove) {
                it.value.eventSink?.timedOut()
                it.value.timeOutRunnable?.run()
            }
            remove
        }
    }
}

private data class PollRegistration(
    var lastAccessed: Long,
    val eventStream: EventStream<*>,
    var eventSink: EventSink<*>?,
    val timeOutRunnable: Runnable?,
)