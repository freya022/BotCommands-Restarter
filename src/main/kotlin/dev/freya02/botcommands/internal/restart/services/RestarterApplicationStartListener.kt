package dev.freya02.botcommands.internal.restart.services

import dev.freya02.botcommands.internal.restart.Restarter
import io.github.freya022.botcommands.api.core.events.ApplicationStartListener
import io.github.freya022.botcommands.api.core.events.BApplicationStartEvent
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.freya022.botcommands.api.core.service.annotations.RequiresDefaultInjection

@BService
@RequiresDefaultInjection
internal class RestarterApplicationStartListener : ApplicationStartListener {

    override fun onApplicationStart(event: BApplicationStartEvent) {
        Restarter.initialize(event.args)
    }
}