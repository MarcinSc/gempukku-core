package com.gempukku.server.polling

import com.gempukku.server.ResponseWriter
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class XmlEventSink(
    private val rootName: String,
    private val pollIdParameter: String,
    private val pollId: String,
    private val responseWriter: ResponseWriter
) : EventSink<(Document) -> Element> {
    override fun processEvents(events: List<(Document) -> Element>) {
        val documentBuilderFactory = DocumentBuilderFactory.newInstance()
        val documentBuilder = documentBuilderFactory.newDocumentBuilder()

        val document = documentBuilder.newDocument()
        val root = document.createElement(rootName)
        root.setAttribute(pollIdParameter, pollId)
        events.forEach {
            root.appendChild(it.invoke(document))
        }
        responseWriter.writeXmlResponse(document)
    }

    override fun timedOut() {
        val documentBuilderFactory = DocumentBuilderFactory.newInstance()
        val documentBuilder = documentBuilderFactory.newDocumentBuilder()

        val document = documentBuilder.newDocument()
        val root = document.createElement(rootName)
        root.setAttribute(pollIdParameter, pollId)

        responseWriter.writeXmlResponse(document)
    }
}