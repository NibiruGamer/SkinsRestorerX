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
package net.skinsrestorer.shared.commands.library;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.Suggestion;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.skinsrestorer.shared.log.SRLogger;
import net.skinsrestorer.shared.subjects.SRCommandSender;
import net.skinsrestorer.shared.subjects.SRPlayer;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class CommandExecutor<T> {
    private final CommandDispatcher<T> dispatcher;
    @Getter
    private final CommandManager<T> manager;
    private final SRCommandMeta meta;
    private final SRLogger logger;

    public void execute(SRCommandSender executor, String input) {
        manager.executeCommand(executor, input);
    }

    public CompletableFuture<List<String>> tabComplete(SRCommandSender executor, String input) {
        if (executor instanceof SRPlayer) {
            logger.debug(String.format("Tab completing: '%s' for '%s'", input, ((SRPlayer) executor).getName()));
        } else {
            logger.debug(String.format("Tab completing: '%s' for console", input));
        }

        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, manager.convertUnsafeCommandSender(executor))).thenApply(suggestions ->
                suggestions.getList().stream().map(Suggestion::getText).collect(Collectors.toList()));
    }

    public boolean hasPermission(SRCommandSender executor) {
        return meta.permission().test(executor);
    }
}
