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
package net.skinsrestorer.sponge.wrapper;

import lombok.experimental.SuperBuilder;
import net.skinsrestorer.shared.subjects.SRPlayer;
import net.skinsrestorer.shared.subjects.SRServerPlayer;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;

import java.util.Locale;
import java.util.UUID;

@SuperBuilder
public class WrapperPlayer extends WrapperCommandSender implements SRServerPlayer {
    private final ServerPlayer player;

    @Override
    public Locale getLocale() {
        return player.locale();
    }

    @Override
    public <P> P getAs(Class<P> playerClass) {
        return playerClass.cast(player);
    }

    @Override
    public UUID getUniqueId() {
        return player.uniqueId();
    }

    @Override
    public String getName() {
        return player.name();
    }

    @Override
    public boolean canSee(SRPlayer player) {
        return this.player.canSee(player.getAs(Player.class));
    }

    @Override
    public void closeInventory() {
        player.closeInventory();
    }
}
