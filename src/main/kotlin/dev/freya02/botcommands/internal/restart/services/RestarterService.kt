package dev.freya02.botcommands.internal.restart.services

import dev.freya02.botcommands.internal.restart.RestartListener
import dev.freya02.botcommands.internal.restart.Restarter
import dev.freya02.botcommands.internal.restart.sources.SourceDirectories
import dev.freya02.botcommands.internal.restart.utils.AppClasspath
import dev.freya02.botcommands.internal.restart.watcher.ClasspathListener
import io.github.freya022.botcommands.api.core.BContext
import io.github.freya022.botcommands.api.core.annotations.BEventListener
import io.github.freya022.botcommands.api.core.config.BRestartConfig
import io.github.freya022.botcommands.api.core.events.BShutdownEvent
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.freya022.botcommands.api.core.service.annotations.RequiresDefaultInjection

@BService
@RequiresDefaultInjection
internal class RestarterService internal constructor (
    context: BContext,
) {

    init {
        Restarter.instance.addListener(object : RestartListener {
            override fun beforeStop() {
                context.shutdown()
                context.awaitShutdown()
            }
        })
    }

    @BService
    internal fun sourceDirectories(config: BRestartConfig): SourceDirectories {
        return SourceDirectories(AppClasspath.getPaths(), ClasspathListener(config.restartDelay))
    }

    @BEventListener(priority = Int.MAX_VALUE)
    internal fun onShutdown(event: BShutdownEvent, sourceDirectories: SourceDirectories) {
        sourceDirectories.close()
    }
}