package io.ktor.client.tests.utils

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.websocket.*

fun Application.tests() {
    install(WebSockets)

    install(Authentication) {
        basic("test-basic") {
            realm = "my-server"
            validate { call ->
                if (call.name == "user1" && call.password == "Password1")
                    UserIdPrincipal("user1")
                else null
            }
        }
    }

    routing {
        post("/echo") {
            val response = call.receiveText()
            call.respond(response)
        }
        get("/bytes") {
            val size = call.request.queryParameters["size"]!!.toInt()
            call.respondBytes(makeArray(size))
        }
        route("/json") {
            get("/users") {
                call.respondText("[{'id': 42, 'login': 'TestLogin'}]", contentType = ContentType.Application.Json)
            }
            get("/photos") {
                call.respondText("[{'id': 4242, 'path': 'cat.jpg'}]", contentType = ContentType.Application.Json)
            }
        }
        route("/compression") {
            route("/deflate") {
                install(Compression) { deflate() }
                setCompressionEndpoints()
            }
            route("/gzip") {
                install(Compression) { gzip() }
                setCompressionEndpoints()
            }
            route("/identity") {
                install(Compression) { identity() }
                setCompressionEndpoints()
            }
        }

        route("/auth") {
            route("/basic") {
                authenticate("test-basic") {
                    post {
                        val requestData = call.receiveText()
                        if (requestData == "{\"test\":\"text\"}")
                            call.respondText("OK")
                        else
                            call.respond(HttpStatusCode.BadRequest)
                    }
                    route("/ws") {
                        route("/echo") {
                            webSocket(protocol = "ocpp2.0,ocpp1.6") {
                                for (message in incoming) {
                                    send(message)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Route.setCompressionEndpoints() {
    get {
        call.respondText("Compressed response!")
    }
}

