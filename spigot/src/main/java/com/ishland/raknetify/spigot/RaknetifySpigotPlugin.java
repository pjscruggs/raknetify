/*
 * This file is a part of the Raknetify project, licensed under MIT.
 *
 * Copyright (c) 2022-2025 ishland
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.ishland.raknetify.spigot;

import com.ishland.raknetify.common.data.ProtocolMultiChannelMappings;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/** Owns companion RakNet listeners for a tested Spigot or Paper server. */
public final class RaknetifySpigotPlugin extends JavaPlugin {
    /** Listener and event-loop owner, absent until the server finishes enabling plugins. */
    private @org.jspecify.annotations.Nullable SpigotListeners listeners;

    /**
     * Defers binding until the native TCP listener exists and the server can report its game
     * version.
     */
    @Override
    public void onEnable() {
        ProtocolMultiChannelMappings.init();
        if (getServer().getPluginManager().isPluginEnabled("voicechat")) {
            SpigotVoice.register(this);
        }
        getServer().getScheduler().runTask(this, this::startListeners);
    }

    /**
     * Starts the UDP transport or disables this plugin after closing every partially opened
     * listener.
     */
    private void startListeners() {
        try {
            ServerAccess access = new ServerAccess(getServer());
            listeners = new SpigotListeners(access);
            listeners.bind();
            getLogger().info("RakNet is listening on UDP port " + getServer().getPort());
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Could not start RakNet transport", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /**
     * Closes only this plugin's UDP channels and event loops, including after a partial startup
     * failure.
     */
    @Override
    public void onDisable() {
        if (listeners != null) {
            listeners.close();
            listeners = null;
        }
    }
}
