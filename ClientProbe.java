package localchecks;

import com.google.gson.Gson;
import com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec;
import com.ishland.raknetify.fabric.mixin.access.IClientConnection;
import io.netty.channel.Channel;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import network.ycc.raknet.RakNet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;

public class ClientProbe implements ClientModInitializer {
    public void onInitializeClient() {
        Thread worker = new Thread(() -> {
            var result = new LinkedHashMap<String, Object>();
            try {
                Minecraft mc = Minecraft.getInstance();
                long deadline = System.nanoTime() + 90_000_000_000L;
                int chunks = 0;
                while (System.nanoTime() < deadline) {
                    CompletableFuture<Integer> sample = new CompletableFuture<>();
                    mc.execute(() -> sample.complete(mc.level == null ? 0 : mc.level.getChunkSource().getLoadedChunksCount()));
                    chunks = sample.get(45, java.util.concurrent.TimeUnit.SECONDS);
                    if (chunks > 0) break;
                    Thread.sleep(250);
                }
                if (chunks == 0) throw new AssertionError("No decoded world chunks");
                Thread.sleep(3000);
                CompletableFuture<Void> checked = new CompletableFuture<>();
                mc.execute(() -> {
                    try {
                        Channel channel = ((IClientConnection) mc.getConnection().getConnection()).getChannel();
                        boolean udp = channel.config() instanceof RakNet.Config;
                        RakNetSimpleMultiChannelCodec codec = channel.pipeline().get(RakNetSimpleMultiChannelCodec.class);
                        boolean multi = false;
                        if (codec != null) {
                            var enabled = codec.getClass().getDeclaredField("isMultichannelEnabled");
                            enabled.setAccessible(true);
                            multi = enabled.getBoolean(codec);
                        }
                        result.put("udp", udp);
                        result.put("multichannel", multi);
                        result.put("chunks", mc.level.getChunkSource().getLoadedChunksCount());
                        result.put("channel", channel.getClass().getName());
                        result.put("pipeline", channel.pipeline().names());
                        boolean encryption = channel.pipeline().get("raknetify-multichannel-encryption") != null
                                || (channel.pipeline().get("encrypt") != null && channel.pipeline().get("decrypt") != null);
                        result.put("encryption", encryption);
                        if (Boolean.getBoolean("probe.encryption") && !encryption) throw new AssertionError("Encryption was not installed");
                        boolean expectedUdp = Boolean.parseBoolean(System.getProperty("probe.udp", "true"));
                        if (udp != expectedUdp || (udp && !multi)) throw new AssertionError("Unexpected transport or inactive multichannel");
                        result.put("passed", true);
                        mc.disconnectFromWorld(Component.literal("Compatibility check complete"));
                        checked.complete(null);
                    } catch (Throwable e) { checked.completeExceptionally(e); }
                });
                checked.get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Throwable e) {
                e.printStackTrace();
                result.put("passed", false);
                result.put("error", e.toString());
            } finally {
                try { Files.writeString(Path.of("probe-result.json"), new Gson().toJson(result)); }
                catch (Exception e) { e.printStackTrace(); }
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
            }
        }, "Compatibility probe");
        worker.setDaemon(true);
        worker.start();
    }
}
