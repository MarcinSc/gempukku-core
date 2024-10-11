package com.gempukku.server

import java.util.regex.Pattern

interface HttpServerSystem {
    fun registerRequestHandler(method: HttpMethod, uriPattern: Pattern, requestHandler: ServerRequestHandler): Runnable
}