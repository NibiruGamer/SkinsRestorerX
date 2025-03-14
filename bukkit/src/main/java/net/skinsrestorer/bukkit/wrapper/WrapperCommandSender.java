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
package net.skinsrestorer.bukkit.wrapper;

import ch.jalu.configme.SettingsManager;
import lombok.experimental.SuperBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.skinsrestorer.bukkit.SRBukkitAdapter;
import net.skinsrestorer.shared.subjects.AbstractSRCommandSender;
import net.skinsrestorer.shared.subjects.messages.SkinsRestorerLocale;
import net.skinsrestorer.shared.subjects.permissions.Permission;
import net.skinsrestorer.shared.utils.Tristate;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;

@SuperBuilder
public class WrapperCommandSender extends AbstractSRCommandSender {
    private final SettingsManager settings;
    private final SkinsRestorerLocale locale;
    private final SRBukkitAdapter adapter;
    private final CommandSender sender;
    private final GsonComponentSerializer serializer = GsonComponentSerializer.gson();

    @Override
    public void sendMessage(String messageJson) {
        Component message = serializer.deserialize(messageJson);

        Runnable runnable = () -> adapter.getAdventure().sender(sender).sendMessage(message);
        if (sender instanceof BlockCommandSender) {
            // Command blocks require messages to be sent synchronously in Bukkit
            adapter.runSync(runnable);
        } else {
            runnable.run();
        }
    }

    @Override
    public boolean hasPermission(Permission permission) {
        return permission.checkPermission(settings, p -> {
            boolean hasPermission = sender.hasPermission(p);

            // If a platform makes a permission false or true, return that value
            boolean explicit = hasPermission || sender.isPermissionSet(p);
            return explicit ? Tristate.fromBoolean(hasPermission) : Tristate.UNDEFINED;
        });
    }

    @Override
    protected SkinsRestorerLocale getSRLocale() {
        return locale;
    }

    @Override
    protected SettingsManager getSettings() {
        return settings;
    }
}
