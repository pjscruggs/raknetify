package localchecks;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.ProxyServer;

public final class BackendSelector {
    private final ProxyServer proxy;

    @Inject
    public BackendSelector(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Subscribe
    public void chooseBackend(PlayerChooseInitialServerEvent event) {
        String backend = switch (event.getPlayer().getProtocolVersion().getProtocol()) {
            case 767 -> "legacy";
            case 777 -> "modern";
            default -> throw new IllegalArgumentException("Unexpected Minecraft protocol");
        };
        event.setInitialServer(proxy.getServer(backend).orElseThrow());
    }
}
