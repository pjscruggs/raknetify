# Raknetify2

<img src="icon.png" alt="Raknetify2" width="256">

Raknetify2 is a Minecraft networking mod that uses RakNet over UDP to help keep multiplayer interactions responsive during packet loss and large world transfers.

## How it works

Minecraft normally uses TCP, which delivers data in one ordered stream. A missing piece can delay everything behind it. Raknetify2 separates traffic into ordering channels, so a delay in one channel need not hold up unrelated traffic in another.

Raknetify2 changes the network transport, not Minecraft's game protocol. Connecting different Minecraft versions still requires a compatible protocol translation setup.

## Downloads

Download from [GitHub Releases](https://github.com/pjscruggs/raknetify2/releases). Choose the file for your platform and check its supported Minecraft versions before installing.

| Platform | File | Install location |
| --- | --- | --- |
| Fabric client or server | `raknetify2-fabric-*.jar` | `mods` |
| Forge client or server | `raknetify2-forge-*.jar` | `mods` |
| NeoForge client or server | `raknetify2-neoforge-*.jar` | `mods` |
| Spigot or Paper server | `raknetify2-spigot-*.jar` | `plugins` |
| Velocity proxy | `raknetify2-velocity-*.jar` | `plugins` |
| BungeeCord proxy | `raknetify2-bungee-*.jar` | `plugins` |

The included `compatibility.json` lists supported Minecraft versions and runtime requirements for Fabric. For Forge, NeoForge and Spigot/Paper, check the Minecraft version declared by that JAR.

Development builds are available from [GitHub Actions](https://github.com/pjscruggs/raknetify2/actions/workflows/build.yml). Open a successful run for `main` and download its `Artifacts` archive. A GitHub account is required to download these builds.

## Direct server setup

1. Install the matching Raknetify2 mod on the client. Install the matching mod or Spigot/Paper plugin on the server using the table above.
2. Allow UDP traffic on the server's Minecraft port, using the same port number as TCP. Configure both the firewall and port forwarding when applicable.
3. Connect using an address such as `raknet;example.com` or `raknet;example.com:25565`.

Install Raknetify2 in place of Raknetify. Do not install both. Normal server addresses continue to use TCP.

Encrypted RakNet connections require the authenticated encryption protocol on both ends. Original Raknetify and older Raknetify2 releases cannot connect to updated clients or servers over encrypted RakNet.

## Proxy setup

Install the matching plugin on Velocity or BungeeCord and the matching client mod on each connecting client. Allow UDP on the proxy's listening port. Clients use the `raknet;` prefix with the proxy address. Backend servers do not need Raknetify2 for this setup.

## UDP proxies and PROXY protocol v2

To preserve client IP addresses behind a UDP proxy, create `config/raknetify2-proxy-protocol.json` on the server or Minecraft proxy receiving the forwarded traffic:

```json
{
  "trustedProxies": ["192.0.2.10"]
}
```

Replace the example with the UDP proxy's actual source IP address or CIDR range, then restart. Configure that proxy to send PROXY protocol v2 headers for UDP. Raknetify2 accepts forwarded client addresses only from the listed sources. Direct UDP connections are rejected while this configuration is enabled. The TCP listener keeps its existing configuration.

This setting trusts the listed proxies to identify clients. Use only addresses you control and restrict backend access in the firewall. An absent file leaves ordinary direct connections enabled. An invalid file prevents the RakNet listener from starting.

## Sharing a port with Simple Voice Chat

This integration supports Fabric, Forge, NeoForge, Spigot and Paper servers. Install [Simple Voice Chat](https://modrepo.de/minecraft/voicechat/wiki/server_setup) on the server and on each client that wants voice chat.

1. Create `config/raknetify2-voicechat.json` in the server directory containing `{"enabled": true}`.
2. Set `port=-1` in Simple Voice Chat's server configuration. The file is `config/voicechat/voicechat-server.properties` on modded servers or `plugins/voicechat/voicechat-server.properties` on Spigot/Paper.
3. Leave `bind_address` empty unless you need a specific interface. An explicit address must match the RakNet listener. If RakNet uses a different port from Minecraft, set the voice `port` to that UDP port instead of `-1`.
4. Restart the server and run `/voicechat test <PLAYERNAME>` to check a connected player's voice connection.

Raknetify2 supplies the shared UDP socket through Simple Voice Chat's API. Simple Voice Chat still authenticates players and encrypts voice traffic. Clients can use voice chat with either TCP or RakNet gameplay connections.

Keep Query and Geyser on separate UDP ports. This integration shares the server socket with Simple Voice Chat only. It does not share the voice-proxy socket on Velocity or BungeeCord. For those networks, follow [Simple Voice Chat's proxy setup](https://modrepo.de/minecraft/voicechat/wiki/proxy_setup) and give the voice proxy its own UDP port. For a generic UDP tunnel, set `voice_host` to the public hostname and UDP port that forwards to this server.

## Automatic transfers

Fabric servers can select RakNet automatically when transferring players to configured destinations. Create `config/raknetify2-transfers.json` on the server that sends the transfer:

```json
{
  "destinations": [
    {
      "host": "play.example.com",
      "tcpPort": 25565,
      "udpPort": 25570
    }
  ]
}
```

Restart the server after editing this file. Transfer players to the ordinary hostname and TCP port. Clients that advertise support for automatic RakNet transfers use the configured UDP port. Other clients and unmatched destinations keep the original transfer address.

The destination must already accept RakNet on that UDP port. This file does not open a listener or configure a firewall. Hostnames and TCP ports must match an entry exactly, apart from hostname capitalization and equivalent IP spelling.

## License and credits

Raknetify2 is an independently maintained fork of [Raknetify](https://github.com/RelativityMC/raknetify), originally developed by ishland. It retains Raknetify's MIT license and original copyright notices. The networking implementation builds on ishland's Raknetify and [netty-raknet](https://github.com/RelativityMC/netty-raknet).
