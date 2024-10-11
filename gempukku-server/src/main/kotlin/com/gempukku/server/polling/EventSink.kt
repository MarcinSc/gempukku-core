package com.gempukku.server.polling

interface EventSink<Event> {
    fun processEvents(events: List<Event>)
    fun timedOut()
}