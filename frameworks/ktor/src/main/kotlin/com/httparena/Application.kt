package com.httparena

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.io.File

fun main() {
    AppData.load()
    println("Ktor HttpArena server starting on :8080")

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(DefaultHeaders) {
            header("Server", "ktor")
        }
        install(Compression) {
            gzip()
        }
        install(ContentNegotiation) {
            json(AppData.json)
        }

        configureRouting()
    }.start(wait = true)
}

private fun Application.configureRouting() {
    fun ApplicationCall.sumQueryParams(): Long =
        request.queryParameters.entries().sumOf { (_, v) ->
            v.sumOf { it.toLongOrNull() ?: 0L }
        }

    routing {
        get("/pipeline") {
            call.respondText("ok", ContentType.Text.Plain)
        }

        get("/baseline11") {
            call.respondText(
                call.sumQueryParams().toString(),
                ContentType.Text.Plain
            )
        }

        post("/baseline11") {
            val sum = call.sumQueryParams()
            val body = call.receiveText().trim().toLongOrNull() ?: run {
                call.respondText(sum.toString(), ContentType.Text.Plain)
                return@post
            }
            call.respondText(
                (sum + body).toString(),
                ContentType.Text.Plain
            )
        }

        get("/baseline2") {
            call.respondText(
                call.sumQueryParams().toString(),
                ContentType.Text.Plain
            )
        }

        get("/json/{count}") {
            if (AppData.dataset.isEmpty()) {
                call.respondText("Dataset not loaded", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                return@get
            }
            var count = call.pathParameters["count"]?.toIntOrNull() ?: 0
            if (count < 0) count = 0
            if (count > AppData.dataset.size) count = AppData.dataset.size
            val m = call.request.queryParameters["m"]?.toIntOrNull() ?: 1
            val processed = AppData.dataset.take(count).map { d ->
                ProcessedItem(
                    id = d.id, name = d.name, category = d.category,
                    price = d.price, quantity = d.quantity, active = d.active,
                    tags = d.tags, rating = d.rating,
                    total = d.price.toLong() * d.quantity * m
                )
            }
            call.respond(JsonResponse(items = processed, count = count))
        }

        get("/async-db") {
            val min = call.request.queryParameters["min"]?.toIntOrNull() ?: 10
            val max = call.request.queryParameters["max"]?.toIntOrNull() ?: 50
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 50)
            try {
                val items = suspendTransaction(AppData.postgres) {
                    with(ItemTable) {
                        selectAll()
                            .where { price.between(min, max) }
                            .limit(limit)
                            .map(::toDbItem)
                            .toList()
                    }
                }
                call.respond(
                    DbResponse(
                        items = items,
                        count = items.size
                    )
                )
            } catch (e: Exception) {
                log.error("Failed to load items from DB", e)
                call.respondBytes("{\"items\":[],\"count\":0}".toByteArray(), ContentType.Application.Json)
            }
        }

        post("/upload") {
            val body = call.receiveChannel()
            val sink = DevNull.asByteWriteChannel()
            val totalBytes = try {
                body.copyTo(sink)
            } finally {
                sink.flushAndClose()
            }
            call.respondText(totalBytes.toString(), ContentType.Text.Plain)
        }

        staticFiles("/static", File("/data/static")) {
            preCompressed(CompressedFileType.BROTLI, CompressedFileType.GZIP)
        }
    }
}
