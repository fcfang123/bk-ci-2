package com.tencent.devops.ai.dao

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class UserLlmConfigDao {

    fun getByUserId(
        dslContext: DSLContext,
        userId: String
    ): UserLlmStoredConfig? {
        return dslContext.select(
            USER_ID,
            BASE_URL,
            MODEL_NAME,
            API_KEY,
            BK_APP_CODE,
            BK_APP_SECRET,
            ENABLED,
            CONNECT_TIMEOUT_SECONDS,
            READ_TIMEOUT_SECONDS,
            WRITE_TIMEOUT_SECONDS,
            EXECUTION_TIMEOUT_SECONDS,
            MAX_ATTEMPTS,
            INITIAL_BACKOFF_SECONDS,
            MAX_BACKOFF_SECONDS,
            BACKOFF_MULTIPLIER,
            CREATED_TIME,
            UPDATED_TIME
        ).from(TABLE)
            .where(USER_ID.eq(userId))
            .fetchOne { record ->
                UserLlmStoredConfig(
                    userId = record.get(USER_ID)!!,
                    baseUrl = record.get(BASE_URL)!!,
                    modelName = record.get(MODEL_NAME)!!,
                    encryptedApiKey = record.get(API_KEY) ?: "",
                    bkAppCode = record.get(BK_APP_CODE) ?: "",
                    encryptedBkAppSecret = record.get(BK_APP_SECRET) ?: "",
                    enabled = record.get(ENABLED) ?: true,
                    connectTimeoutSeconds = record.get(CONNECT_TIMEOUT_SECONDS) ?: 10,
                    readTimeoutSeconds = record.get(READ_TIMEOUT_SECONDS) ?: 90,
                    writeTimeoutSeconds = record.get(WRITE_TIMEOUT_SECONDS) ?: 30,
                    executionTimeoutSeconds = record.get(EXECUTION_TIMEOUT_SECONDS) ?: 60,
                    maxAttempts = record.get(MAX_ATTEMPTS) ?: 5,
                    initialBackoffSeconds = record.get(INITIAL_BACKOFF_SECONDS) ?: 1,
                    maxBackoffSeconds = record.get(MAX_BACKOFF_SECONDS) ?: 8,
                    backoffMultiplier = record.get(BACKOFF_MULTIPLIER) ?: 2.0,
                    createdTime = record.get(CREATED_TIME)!!,
                    updatedTime = record.get(UPDATED_TIME)!!
                )
            }
    }

    fun upsert(
        dslContext: DSLContext,
        config: UserLlmStoredConfig
    ): Int {
        return dslContext.insertInto(
            TABLE,
            USER_ID,
            BASE_URL,
            MODEL_NAME,
            API_KEY,
            BK_APP_CODE,
            BK_APP_SECRET,
            ENABLED,
            CONNECT_TIMEOUT_SECONDS,
            READ_TIMEOUT_SECONDS,
            WRITE_TIMEOUT_SECONDS,
            EXECUTION_TIMEOUT_SECONDS,
            MAX_ATTEMPTS,
            INITIAL_BACKOFF_SECONDS,
            MAX_BACKOFF_SECONDS,
            BACKOFF_MULTIPLIER,
            CREATED_TIME,
            UPDATED_TIME
        ).values(
            config.userId,
            config.baseUrl,
            config.modelName,
            config.encryptedApiKey,
            config.bkAppCode,
            config.encryptedBkAppSecret,
            config.enabled,
            config.connectTimeoutSeconds,
            config.readTimeoutSeconds,
            config.writeTimeoutSeconds,
            config.executionTimeoutSeconds,
            config.maxAttempts,
            config.initialBackoffSeconds,
            config.maxBackoffSeconds,
            config.backoffMultiplier,
            config.createdTime,
            config.updatedTime
        ).onDuplicateKeyUpdate()
            .set(BASE_URL, config.baseUrl)
            .set(MODEL_NAME, config.modelName)
            .set(API_KEY, config.encryptedApiKey)
            .set(BK_APP_CODE, config.bkAppCode)
            .set(BK_APP_SECRET, config.encryptedBkAppSecret)
            .set(ENABLED, config.enabled)
            .set(CONNECT_TIMEOUT_SECONDS, config.connectTimeoutSeconds)
            .set(READ_TIMEOUT_SECONDS, config.readTimeoutSeconds)
            .set(WRITE_TIMEOUT_SECONDS, config.writeTimeoutSeconds)
            .set(EXECUTION_TIMEOUT_SECONDS, config.executionTimeoutSeconds)
            .set(MAX_ATTEMPTS, config.maxAttempts)
            .set(INITIAL_BACKOFF_SECONDS, config.initialBackoffSeconds)
            .set(MAX_BACKOFF_SECONDS, config.maxBackoffSeconds)
            .set(BACKOFF_MULTIPLIER, config.backoffMultiplier)
            .set(UPDATED_TIME, config.updatedTime)
            .execute()
    }

    fun delete(
        dslContext: DSLContext,
        userId: String
    ): Int {
        return dslContext.deleteFrom(TABLE)
            .where(USER_ID.eq(userId))
            .execute()
    }

    companion object {
        private val TABLE = DSL.table(DSL.name("T_AI_USER_LLM_CONFIG"))

        private val USER_ID = DSL.field(DSL.name("USER_ID"), String::class.java)
        private val BASE_URL = DSL.field(DSL.name("BASE_URL"), String::class.java)
        private val MODEL_NAME = DSL.field(DSL.name("MODEL_NAME"), String::class.java)
        private val API_KEY = DSL.field(DSL.name("API_KEY"), String::class.java)
        private val BK_APP_CODE = DSL.field(DSL.name("BK_APP_CODE"), String::class.java)
        private val BK_APP_SECRET = DSL.field(DSL.name("BK_APP_SECRET"), String::class.java)
        private val ENABLED = DSL.field(DSL.name("ENABLED"), Boolean::class.java)
        private val CONNECT_TIMEOUT_SECONDS =
            DSL.field(DSL.name("CONNECT_TIMEOUT_SECONDS"), Long::class.java)
        private val READ_TIMEOUT_SECONDS =
            DSL.field(DSL.name("READ_TIMEOUT_SECONDS"), Long::class.java)
        private val WRITE_TIMEOUT_SECONDS =
            DSL.field(DSL.name("WRITE_TIMEOUT_SECONDS"), Long::class.java)
        private val EXECUTION_TIMEOUT_SECONDS =
            DSL.field(DSL.name("EXECUTION_TIMEOUT_SECONDS"), Long::class.java)
        private val MAX_ATTEMPTS = DSL.field(DSL.name("MAX_ATTEMPTS"), Int::class.java)
        private val INITIAL_BACKOFF_SECONDS =
            DSL.field(DSL.name("INITIAL_BACKOFF_SECONDS"), Long::class.java)
        private val MAX_BACKOFF_SECONDS =
            DSL.field(DSL.name("MAX_BACKOFF_SECONDS"), Long::class.java)
        private val BACKOFF_MULTIPLIER =
            DSL.field(DSL.name("BACKOFF_MULTIPLIER"), Double::class.java)
        private val CREATED_TIME =
            DSL.field(DSL.name("CREATED_TIME"), LocalDateTime::class.java)
        private val UPDATED_TIME =
            DSL.field(DSL.name("UPDATED_TIME"), LocalDateTime::class.java)
    }
}

data class UserLlmStoredConfig(
    val userId: String,
    val baseUrl: String,
    val modelName: String,
    val encryptedApiKey: String,
    val bkAppCode: String,
    val encryptedBkAppSecret: String,
    val enabled: Boolean,
    val connectTimeoutSeconds: Long,
    val readTimeoutSeconds: Long,
    val writeTimeoutSeconds: Long,
    val executionTimeoutSeconds: Long,
    val maxAttempts: Int,
    val initialBackoffSeconds: Long,
    val maxBackoffSeconds: Long,
    val backoffMultiplier: Double,
    val createdTime: LocalDateTime,
    val updatedTime: LocalDateTime
)
