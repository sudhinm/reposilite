/*
 * Copyright (c) 2021 dzikoysk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.reposilite

import com.reposilite.auth.AuthenticationFacade
import com.reposilite.config.Configuration
import com.reposilite.console.ConsoleFacade
import com.reposilite.failure.FailureFacade
import com.reposilite.frontend.FrontendFacade
import com.reposilite.maven.MavenFacade
import com.reposilite.shared.CachedLogger
import com.reposilite.shared.TimeUtils
import com.reposilite.statistics.StatisticsFacade
import com.reposilite.token.AccessTokenFacade
import com.reposilite.web.ReposiliteContextFactory
import com.reposilite.web.WebServer
import net.dzikoysk.dynamiclogger.Channel
import net.dzikoysk.dynamiclogger.Journalist
import net.dzikoysk.dynamiclogger.Logger
import net.dzikoysk.dynamiclogger.backend.AggregatedLogger
import panda.utilities.console.Effect
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

const val VERSION = "3.0.0-SNAPSHOT"

class Reposilite(
    logger: Logger,
    val configuration: Configuration,
    val workingDirectory: Path,
    val testEnv: Boolean,
    val webServer: WebServer,
    val contextFactory: ReposiliteContextFactory,
    val failureFacade: FailureFacade,
    val authenticationFacade: AuthenticationFacade,
    val mavenFacade: MavenFacade,
    val consoleFacade: ConsoleFacade,
    val accessTokenFacade: AccessTokenFacade,
    val frontendFacade: FrontendFacade,
    val statisticsFacade: StatisticsFacade,
) : Journalist {

    val cachedLogger = CachedLogger(Channel.ALL, configuration.cachedLogSize)
    private val logger = AggregatedLogger(logger, cachedLogger)

    private val alive = AtomicBoolean(false)
    private val shutdownHook = Thread { shutdown() }
    private val uptime = System.currentTimeMillis()

    fun launch() {
        load()
        start()
    }

    fun load() {
        logger.info("")
        logger.info("${Effect.GREEN}Reposilite ${Effect.RESET}$VERSION")
        logger.info("")
        logger.info("--- Environment")

        if (testEnv) {
            logger.info("Test environment enabled")
        }

        logger.info("Platform: ${System.getProperty("java.version")} (${System.getProperty("os.name")})")
        logger.info("Working directory: ${workingDirectory.toAbsolutePath()}")
        logger.info("")

        logger.info("--- Loading domain configurations")
        ReposiliteWebConfiguration.initialize(this)
        logger.info("")

        logger.info("--- Repositories")
        mavenFacade.getRepositories().forEach { logger.info("+ ${it.name} (${it.visibility.toString().lowercase()})") }
        logger.info("${mavenFacade.getRepositories().size} repositories have been found")
        logger.info("")
    }

    private fun start(): Reposilite {
        alive.set(true)
        Thread.currentThread().name = "Reposilite | Main Thread"

        try {
            logger.info("Binding server at ${configuration.hostname}::${configuration.port}")
            webServer.start(this)
            Runtime.getRuntime().addShutdownHook(shutdownHook)

            logger.info("Done (" + TimeUtils.format(getUptime() / 1000.0) + "s)!")
            consoleFacade.executeCommand("help")

            CompletableFuture.runAsync {
                logger.info("Collecting status metrics...")
                consoleFacade.executeCommand("status")
            }
        } catch (exception: Exception) {
            logger.error("Failed to start Reposilite")
            logger.exception(exception)
            shutdown()
            return this
        }

        return this
    }

    @Synchronized
    fun shutdown() {
        if (!alive.get()) {
            return
        }

        alive.set(false)
        Runtime.getRuntime().removeShutdownHook(shutdownHook)

        logger.info("Shutting down ${configuration.hostname}::${configuration.port} ...")
        ReposiliteWebConfiguration.dispose(this)
        webServer.stop()
    }

    fun getUptime(): Long =
        System.currentTimeMillis() - uptime

    fun getPrettyUptime(): String =
        TimeUtils.format(TimeUtils.getUptime(uptime) / 60) + "min"

    override fun getLogger(): Logger =
        logger

}