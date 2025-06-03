package dev.freya02.botcommands.restart.jda.cache;

import io.github.freya022.botcommands.api.core.JDAService;
import io.github.freya022.botcommands.api.core.events.BReadyEvent;
import net.dv8tion.jda.api.hooks.IEventManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public class Test extends JDAService {

    @NotNull
    @Override
    public Set<GatewayIntent> getIntents() {
        return Set.of();
    }

    @NotNull
    @Override
    public Set<CacheFlag> getCacheFlags() {
        return Set.of();
    }

    @Override
    protected void createJDA(@NotNull BReadyEvent bReadyEvent, @NotNull IEventManager iEventManager) {
        System.out.println("Test");
    }

    void something(BReadyEvent bReadyEvent, IEventManager iEventManager) {
        var a = "a";
        if (a == null) {
            System.out.println("null");
            return;
        }

        System.out.println("not null");
    }
}
