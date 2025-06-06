package dev.freya02.botcommands.internal.restart.services

import dev.freya02.botcommands.internal.restart.RestartListener
import dev.freya02.botcommands.internal.restart.Restarter
import dev.freya02.botcommands.internal.restart.watcher.ClasspathWatcher
import io.github.freya022.botcommands.api.core.BContext
import io.github.freya022.botcommands.api.core.config.BRestartConfig
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.freya022.botcommands.api.core.service.annotations.RequiresDefaultInjection

@BService
@RequiresDefaultInjection
internal class RestarterService internal constructor (
    context: BContext,
    config: BRestartConfig,
) {

    init {
        Restarter.instance.addListener(object : RestartListener {
            override fun beforeStop() {
                context.shutdownNow()
                context.awaitShutdown(context.config.shutdownTimeout)
            }
        })
        ClasspathWatcher.initialize(config.restartDelay)
    }
}