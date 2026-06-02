package com.httparena

import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import java.io.File
import java.net.URI

object DevNull : RawSink {
    override fun close() {}
    override fun flush() {}
    override fun write(source: Buffer, byteCount: Long) {}
}

object AppData {
    val json = Json { ignoreUnknownKeys = true }
    var dataset: List<DatasetItem> = emptyList()
    lateinit var postgres: R2dbcDatabase

    fun load() {
        // Read dataset from file
        val path = System.getenv("DATASET_PATH") ?: "/data/dataset.json"
        val dataFile = File(path)
        if (dataFile.exists()) {
            dataset = json.decodeFromString<List<DatasetItem>>(dataFile.readText())
        }

        // PostgreSQL connection
        val dbUrl = System.getenv("DATABASE_URL")
        if (!dbUrl.isNullOrEmpty()) {
            try {
                val uri = URI(dbUrl.replace("postgres://", "postgresql://"))
                val host = uri.host
                val port = if (uri.port > 0) uri.port else 5432
                val database = uri.path.removePrefix("/")
                val userInfo = uri.userInfo.split(":")
                postgres = R2dbcDatabase.connect {
                    setUrl("r2dbc:postgresql://$host:$port/$database")
                    connectionFactoryOptions {
                        option(ConnectionFactoryOptions.DRIVER, "postgresql")
                        option(ConnectionFactoryOptions.USER, userInfo[0])
                        option(ConnectionFactoryOptions.PASSWORD, if (userInfo.size > 1) userInfo[1] else "")
                    }
                }
            } catch (e: Exception) {
                System.err.println("PG pool init failed: $e")
            }
        }
    }

}
