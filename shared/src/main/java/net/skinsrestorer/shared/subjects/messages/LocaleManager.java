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
package net.skinsrestorer.shared.subjects.messages;

import lombok.NoArgsConstructor;

import javax.inject.Inject;
import java.util.*;

@NoArgsConstructor(onConstructor_ = @Inject)
public class LocaleManager {
    private final Map<Message, Map<Locale, String>> messages = new EnumMap<>(Message.class);
    private final Locale defaultLocale = Locale.ENGLISH;

    public void addMessage(Message key, Locale locale, String message) {
        messages.computeIfAbsent(key, k -> new HashMap<>()).put(locale, message);
    }

    public void verifyValid() {
        for (Message message : Message.values()) {
            if (!messages.containsKey(message)) {
                throw new IllegalStateException(String.format("Message %s not found", message.name()));
            }
        }

        for (Message message : Message.values()) {
            Map<Locale, String> localeMap = messages.get(message);

            if (!localeMap.containsKey(defaultLocale)) {
                throw new IllegalStateException(String.format("Message %s does not have a default translation", message.name()));
            }
        }
    }

    public String getMessage(Locale locale, Message key) {
        Map<Locale, String> localeMap = messages.get(key);

        // First try language_country, then language and finally default
        return Optional.ofNullable(localeMap.get(new Locale(locale.getLanguage(), locale.getCountry())))
                .orElseGet(() -> Optional.ofNullable(localeMap.get(new Locale(locale.getLanguage())))
                        .orElse(localeMap.get(defaultLocale)));
    }
}
