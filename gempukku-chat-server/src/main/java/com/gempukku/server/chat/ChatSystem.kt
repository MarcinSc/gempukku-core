package com.gempukku.server.chat

import com.gempukku.context.processor.inject.Inject
import com.gempukku.context.resolver.expose.Exposes
import java.util.function.Predicate

@Exposes(ChatInterface::class)
class ChatSystem : ChatInterface {
    @Inject(allowsNull = true)
    private var ignoreSystem: IgnoreSystem? = null

    private val chatRooms: MutableMap<String, ChatRoom> = mutableMapOf()

    override fun createChatRoom(
        roomName: String,
        allowIncognito: Boolean,
        commands: Map<String, ChatCommandCallback>,
        welcomeMessage: String?,
        userPredicate: Predicate<String>,
    ): Runnable? {
        if (chatRooms.containsKey(roomName))
            return null

        val chatRoom = ChatRoom(allowIncognito, commands, welcomeMessage, userPredicate)

        chatRooms[roomName] = chatRoom

        return Runnable {
            chatRoom.close()
            chatRooms.remove(roomName)
        }
    }

    override fun joinUser(roomName: String, playerId: String, admin: Boolean, chatStream: ChatStream): Runnable? {
        return chatRooms[roomName]?.joinUser(
            playerId,
            admin,
            { !(ignoreSystem?.isIgnored(playerId, it) ?: false) },
            chatStream
        )
    }

    override fun setIncognito(roomName: String, playerId: String, incognito: Boolean) {
        chatRooms[roomName]?.setIncognito(playerId, incognito)
    }

    override fun sendMessage(roomName: String, playerId: String, message: String, admin: Boolean) {
        chatRooms[roomName]?.sendMessage(playerId, message, admin)
    }

    override fun sendToUser(roomName: String, from: String, to: String, message: String, admin: Boolean) {
        chatRooms[roomName]?.sendToUser(from, to, message, admin)
    }
}