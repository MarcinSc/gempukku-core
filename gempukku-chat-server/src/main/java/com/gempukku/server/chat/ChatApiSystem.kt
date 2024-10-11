package com.gempukku.server.chat

import com.gempukku.context.lifecycle.LifecycleObserver
import com.gempukku.context.processor.inject.Inject
import com.gempukku.context.processor.inject.InjectProperty
import com.gempukku.context.resolver.expose.Exposes
import com.gempukku.server.HttpMethod
import com.gempukku.server.HttpProcessingException
import com.gempukku.server.HttpRequest
import com.gempukku.server.HttpServerSystem
import com.gempukku.server.ResponseWriter
import com.gempukku.server.chat.long.GatheringXmlChatStream
import com.gempukku.server.login.LoggedUserSystem
import com.gempukku.server.login.getActingAsUser
import com.gempukku.server.polling.LongPolling
import com.gempukku.server.polling.XmlEventSink
import com.gempukku.server.polling.createRootElement
import java.util.regex.Pattern

@Exposes(LifecycleObserver::class)
class ChatApiSystem(private val urlPrefix: String) : LifecycleObserver {
    @Inject
    private lateinit var chat: ChatInterface

    @Inject
    private lateinit var server: HttpServerSystem

    @Inject
    private lateinit var loggedUserSystem: LoggedUserSystem

    @Inject
    private lateinit var longPolling: LongPolling

    @InjectProperty("roles.admin")
    private lateinit var adminRole: String

    @InjectProperty("parameterNames.actAsParameter")
    private lateinit var actAsParameter: String

    @InjectProperty("parameterNames.pollId")
    private lateinit var pollIdParameterName: String

    private val deregistration: MutableList<Runnable> = mutableListOf()

    override fun afterContextStartup() {
        deregistration.add(
            server.registerRequestHandler(
                HttpMethod.GET, Pattern.compile("$urlPrefix/.*"),
                executeGetChat()
            )
        )
        deregistration.add(
            server.registerRequestHandler(
                HttpMethod.POST, Pattern.compile("$urlPrefix/.*"),
                executePostChat()
            )
        )
    }

    private fun executeGetChat(): (uri: String, request: HttpRequest, remoteIp: String, responseWriter: ResponseWriter) -> Unit =
        { uri, request, _, responseWriter ->
            val roomName = uri.substring(urlPrefix.length + 1)
            val actAsUser =
                getActingAsUser(loggedUserSystem, request, adminRole, request.getQueryParameter(actAsParameter))
            val gatheringChatStream = GatheringXmlChatStream()
            val added = chat.joinUser(
                roomName,
                actAsUser.playerId,
                actAsUser.roles.contains(adminRole),
                gatheringChatStream
            )
            if (added == null) {
                throw HttpProcessingException(404)
            }
            val pollId = longPolling.registerLongPoll(gatheringChatStream.gatheringStream, added)
            longPolling.registerSink(
                pollId, XmlEventSink(
                    createRootElement("chat", pollIdParameterName, pollId),
                    responseWriter
                )
            )
        }

    private fun executePostChat(): (uri: String, request: HttpRequest, remoteIp: String, responseWriter: ResponseWriter) -> Unit =
        { uri, request, _, responseWriter ->
            val roomName = uri.substring(urlPrefix.length + 1)
            val actAsUserSystem =
                getActingAsUser(loggedUserSystem, request, adminRole, request.getFormParameter(actAsParameter))
            val message = request.getFormParameter("message")

            if (message != null && message.trim().isNotEmpty()) {
                chat.sendMessage(roomName, actAsUserSystem.playerId, message, actAsUserSystem.roles.contains(adminRole))
            }

            val pollId = request.getFormParameter(pollIdParameterName) ?: throw HttpProcessingException(404)
            val found =
                longPolling.registerSink(
                    pollId, XmlEventSink(
                        createRootElement("chat", pollIdParameterName, pollId),
                        responseWriter
                    )
                )
            if (!found)
                throw HttpProcessingException(404)
        }

    override fun beforeContextStopped() {
        deregistration.forEach {
            it.run()
        }
        deregistration.clear()
    }
}
