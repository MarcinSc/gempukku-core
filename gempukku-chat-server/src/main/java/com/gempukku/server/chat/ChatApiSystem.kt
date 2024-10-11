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
import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.renderer.html.HtmlWriter
import java.util.*
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
    private val markdownParser: Parser
    private val markdownRenderer: HtmlRenderer

    init {
        val adminExt: List<Extension> = listOf(StrikethroughExtension.create(), AutolinkExtension.create())
        markdownParser = Parser.builder()
            .extensions(adminExt)
            .build()
        markdownRenderer = HtmlRenderer.builder()
            .nodeRendererFactory { htmlContext ->
                LinkShredder(
                    htmlContext
                )
            }
            .extensions(adminExt)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .softbreak("<br />")
            .build()

    }

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

    private val quoteExtender: Pattern = Pattern.compile("^([ \t]*>[ \t]*.+)(?=\n[ \t]*[^>])", Pattern.MULTILINE)

    private fun executePostChat(): (uri: String, request: HttpRequest, remoteIp: String, responseWriter: ResponseWriter) -> Unit =
        { uri, request, _, responseWriter ->
            val roomName = uri.substring(urlPrefix.length)
            val actAsUserSystem =
                getActingAsUser(loggedUserSystem, request, adminRole, request.getFormParameter(actAsParameter))
            val message = request.getFormParameter("message")

            if (message != null && message.trim().length > 0) {
                var newMsg: String
                newMsg = message.trim { it <= ' ' }.replace("\n\n\n+".toRegex(), "\n\n\n")
                newMsg = quoteExtender.matcher(newMsg).replaceAll("$1\n")

                //Escaping underscores so that URLs with lots of underscores (i.e. wiki links) aren't mangled
                // Besides, who uses _this_ instead of *this*?
                newMsg = newMsg.replace("_", "\\_")

                //Need to preserve any commands being made
                if (!newMsg.startsWith("/")) {
                    newMsg = markdownRenderer.render(markdownParser.parse(newMsg))
                    // Prevent quotes with newlines from displaying side-by-side
                    newMsg =
                        newMsg.replace("</blockquote>[\n \t]*<blockquote>".toRegex(), "</blockquote><br /><blockquote>")
                    //Make all links open in a new tab
                    newMsg = newMsg.replace("<(a href=\".*?\")>".toRegex(), "<$1 target=\"blank\">")
                }

                chat.sendMessage(roomName, actAsUserSystem.playerId, newMsg, actAsUserSystem.roles.contains(adminRole))
            }

            val pollId = request.getFormParameter(pollIdParameterName) ?: throw HttpProcessingException(404)
            val found =
                longPolling.registerSink(pollId, XmlEventSink("chat", pollIdParameterName, pollId, responseWriter))
            if (!found)
                throw HttpProcessingException(404)
        }

    private fun executeGetChat(): (uri: String, request: HttpRequest, remoteIp: String, responseWriter: ResponseWriter) -> Unit =
        { uri, request, _, responseWriter ->
            val roomName = uri.substring(urlPrefix.length)
            val actAsUser =
                getActingAsUser(loggedUserSystem, request, adminRole, request.getQueryParameter(actAsParameter))
            val gatheringChatStream = GatheringXmlChatStream()
            // TODO: Finish ignoring people
            val added = chat.joinUser(
                roomName,
                actAsUser.playerId,
                actAsUser.roles.contains(adminRole),
                gatheringChatStream
            )
            if (added != null) {
                throw HttpProcessingException(404)
            }
            val pollId = longPolling.registerLongPoll(gatheringChatStream.gatheringStream, added)
            longPolling.registerSink(pollId, XmlEventSink("chat", pollIdParameterName, pollId, responseWriter))
        }

    override fun beforeContextStopped() {
        deregistration.forEach {
            it.run()
        }
        deregistration.clear()
    }

    //Processing to implement:
    // + quotes restricted to one line
    // - triple quote to avoid this??
    // + remove url text processing
    // + remove image processing
    // - re-enable bare url linking
    private inner class LinkShredder(context: HtmlNodeRendererContext) : NodeRenderer {
        private val html: HtmlWriter = context.writer

        override fun getNodeTypes(): Set<Class<out Node>> {
            // Return the node types we want to use this renderer for.
            return HashSet(
                Arrays.asList(
                    Link::class.java,
                    Image::class.java
                )
            )
        }

        override fun render(node: Node) {
            when (node) {
                is Link -> {
                    if (node.title != null) {
                        html.text(node.title + ": " + node.destination)
                    } else {
                        node.firstChild?.takeIf { it is Text && it.literal != node.destination }?.let {
                            html.text((node.firstChild as Text).literal + ": " + node.destination)
                        } ?: run {
                            html.tag("a", Collections.singletonMap("href", node.destination))
                            html.text(node.destination)
                            html.tag("/a")
                        }
                    }
                }

                is Image -> {
                    html.text(node.title + ": " + node.destination)
                }
            }
        }
    }

}
