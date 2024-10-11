package com.gempukku.server

import io.netty.handler.codec.http.HttpRequest

fun interface ServerRequestHandler {
    fun handleRequest(uri: String, request: HttpRequest, remoteIp: String, responseWriter: ResponseWriter)
}