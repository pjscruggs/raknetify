package localchecks;

import com.google.gson.Gson;
import com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec;
import com.ishland.raknetify.fabric.mixin.access.IClientConnection;
import net.fabricmc.api.ClientModInitializer;
import network.ycc.raknet.RakNet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class LegacyClientProbe implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Thread.ofPlatform().daemon().name("Compatibility probe").start(this::observe);
    }

    private void observe() {
        var result = new LinkedHashMap<String, Object>();
        Object client = null;
        try {
            var type = Class.forName("net.minecraft.class_310");
            client = type.getMethod("method_1551").invoke(null);
            var activeClient = client;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (System.nanoTime() < deadline) {
                var sample = new CompletableFuture<Boolean>();
                ((Executor) client).execute(() -> {
                    try {
                        sample.complete(sample(activeClient, result));
                    } catch (Exception error) {
                        sample.completeExceptionally(error);
                    }
                });
                if (sample.get(30, TimeUnit.SECONDS)) {
                    result.put("passed", true);
                    return;
                }
                Thread.sleep(250);
            }
            throw new IllegalStateException("No loaded chunks with active RakNet multichannel");
        } catch (Exception error) {
            result.put("passed", false);
            result.put("error", error.toString());
            error.printStackTrace();
        } finally {
            try {
                Files.writeString(Path.of("probe-result.json"), new Gson().toJson(result));
                if (client != null) client.getClass().getMethod("method_1592").invoke(client);
            } catch (Exception error) {
                error.printStackTrace();
            }
        }
    }

    private boolean sample(Object client, LinkedHashMap<String, Object> result) throws Exception {
        var world = client.getClass().getField("field_1687").get(client);
        if (world == null) return false;
        var chunks = world.getClass().getMethod("method_2935").invoke(world);
        int count = (int) chunks.getClass().getMethod("method_14151").invoke(chunks);
        var handler = client.getClass().getMethod("method_1562").invoke(client);
        if (handler == null || count == 0) return false;
        var connection = (IClientConnection) handler.getClass().getMethod("method_48296").invoke(handler);
        var channel = connection.getChannel();
        var codec = channel.pipeline().get(RakNetSimpleMultiChannelCodec.class);
        boolean udp = channel.config() instanceof RakNet.Config;
        boolean multi = false;
        if (codec != null) {
            var enabled = codec.getClass().getDeclaredField("isMultichannelEnabled");
            enabled.setAccessible(true);
            multi = enabled.getBoolean(codec);
        }
        result.put("chunks", count);
        result.put("udp", udp);
        result.put("multichannel", multi);
        return udp && multi;
    }
}
