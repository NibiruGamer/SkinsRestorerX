/*
 * SkinsRestorer
 * Copyright (C) 2024  SkinsRestorer Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.skinsrestorer.bungee.wrapper;

import lombok.experimental.SuperBuilder;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.skinsrestorer.shared.subjects.SRPlayer;
import net.skinsrestorer.shared.subjects.SRProxyPlayer;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@SuperBuilder
public class WrapperPlayer extends WrapperCommandSender implements SRProxyPlayer {
    private final ProxiedPlayer player;

    @Override
    public Locale getLocale() {
        Locale playerLocale = player.getLocale();
        if (playerLocale == null) {
            return super.getLocale();
        }

        return playerLocale;
    }

    @Override
    public <P> P getAs(Class<P> playerClass) {
        return playerClass.cast(player);
    }

    @Override
    public Optional<String> getCurrentServer() {
        return Optional.ofNullable(player.getServer()).map(server -> server.getInfo().getName());
    }

    @Override
    public void sendDataToServer(String channel, byte[] data) {
        player.getServer().sendData(channel, data);
    }

    @Override
    public UUID getUniqueId() {
        return player.getUniqueId();
    }

    @Override
    public String getName() {
        return player.getName();
    }

    @Override
    public boolean canSee(SRPlayer player) {
        return true;
    }
}
