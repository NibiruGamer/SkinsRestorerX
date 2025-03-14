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
package net.skinsrestorer.shared.connections;

import ch.jalu.configme.SettingsManager;
import lombok.RequiredArgsConstructor;
import net.skinsrestorer.api.PropertyUtils;
import net.skinsrestorer.api.connections.MineSkinAPI;
import net.skinsrestorer.api.connections.model.MineSkinResponse;
import net.skinsrestorer.api.exception.DataRequestException;
import net.skinsrestorer.api.exception.MineSkinException;
import net.skinsrestorer.api.property.SkinProperty;
import net.skinsrestorer.api.property.SkinVariant;
import net.skinsrestorer.shared.config.APIConfig;
import net.skinsrestorer.shared.connections.http.HttpClient;
import net.skinsrestorer.shared.connections.http.HttpResponse;
import net.skinsrestorer.shared.connections.responses.mineskin.MineSkinErrorDelayResponse;
import net.skinsrestorer.shared.connections.responses.mineskin.MineSkinErrorResponse;
import net.skinsrestorer.shared.connections.responses.mineskin.MineSkinUrlResponse;
import net.skinsrestorer.shared.exception.DataRequestExceptionShared;
import net.skinsrestorer.shared.exception.MineSkinExceptionShared;
import net.skinsrestorer.shared.log.SRLogLevel;
import net.skinsrestorer.shared.log.SRLogger;
import net.skinsrestorer.shared.subjects.messages.Message;
import net.skinsrestorer.shared.subjects.messages.SkinsRestorerLocale;
import net.skinsrestorer.shared.utils.MetricsCounter;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@RequiredArgsConstructor(onConstructor_ = @Inject)
public class MineSkinAPIImpl implements MineSkinAPI {
    private static final URI MINESKIN_ENDPOINT = URI.create("https://api.mineskin.org/generate/url/");
    private static final String NAMEMC_SKIN_URL = "https://namemc.com/skin/";
    private static final String NAMEMC_IMG_URL = "https://s.namemc.com/i/%s.png";
    private final ExecutorService executorService = Executors.newSingleThreadExecutor((Runnable r) -> {
        Thread t = new Thread(r);
        t.setName("SkinsRestorer-MineSkinAPI");
        return t;
    });
    private final SRLogger logger;
    private final MetricsCounter metricsCounter;
    private final SettingsManager settings;
    private final SkinsRestorerLocale locale;
    private final HttpClient httpClient;

    @Override
    public MineSkinResponse genSkin(String imageUrl, @Nullable SkinVariant skinVariant) throws DataRequestException, MineSkinException {
        String resultUrl = imageUrl.startsWith(NAMEMC_SKIN_URL) ? NAMEMC_IMG_URL.replace("%s", imageUrl.substring(24)) : imageUrl; // Fix NameMC skins
        AtomicInteger retryAttempts = new AtomicInteger(0);

        do {
            try {
                Optional<MineSkinResponse> optional = CompletableFuture.supplyAsync(() -> {
                    try {
                        return genSkinInternal(resultUrl, skinVariant);
                    } catch (DataRequestException | MineSkinException e) {
                        throw new CompletionException(e);
                    } catch (IOException e) {
                        logger.debug(SRLogLevel.WARNING, "[ERROR] MineSkin Failed! IOException (connection/disk): (" + resultUrl + ")", e);
                        throw new CompletionException(new DataRequestExceptionShared(e));
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }, executorService).join();

                if (optional.isPresent()) {
                    return optional.get();
                }

                retryAttempts.incrementAndGet();
            } catch (CompletionException e) {
                if (e.getCause() instanceof DataRequestException) {
                    throw new DataRequestExceptionShared(e.getCause());
                } else if (e.getCause() instanceof MineSkinException) {
                    throw new MineSkinExceptionShared((MineSkinException) e.getCause());
                } else {
                    throw new RuntimeException(e);
                }
            }
        } while (retryAttempts.get() < 5);

        throw new MineSkinExceptionShared(Message.ERROR_MS_API_FAILED);
    }

    private Optional<MineSkinResponse> genSkinInternal(String imageUrl, @Nullable SkinVariant skinVariant) throws DataRequestException, MineSkinException, IOException, InterruptedException {
        String skinVariantString = skinVariant != null ? "&variant=" + skinVariant.name().toLowerCase(Locale.ROOT) : "";

        HttpResponse response = queryURL("url=" + URLEncoder.encode(imageUrl, StandardCharsets.UTF_8) + skinVariantString);
        logger.debug("MineSkinAPI: Response: " + response);

        switch (response.statusCode()) {
            case 200 -> {
                MineSkinUrlResponse urlResponse = response.getBodyAs(MineSkinUrlResponse.class);
                SkinProperty property = SkinProperty.of(urlResponse.getData().getTexture().getValue(),
                        urlResponse.getData().getTexture().getSignature());
                return Optional.of(MineSkinResponse.of(property, urlResponse.getIdStr(),
                        skinVariant, PropertyUtils.getSkinVariant(property)));
            }
            case 500, 400 -> {
                MineSkinErrorResponse errorResponse = response.getBodyAs(MineSkinErrorResponse.class);
                String error = errorResponse.getErrorCode();
                logger.debug(String.format("[ERROR] MineSkin Failed! Reason: %s Image URL: %s", error, imageUrl));
                // try again
                return switch (error) {
                    case "failed_to_create_id", "skin_change_failed" -> {
                        logger.debug("Trying again in 5 seconds...");
                        TimeUnit.SECONDS.sleep(5);
                        yield Optional.empty();
                    }
                    case "no_account_available" -> throw new MineSkinExceptionShared(Message.ERROR_MS_FULL);
                    default -> throw new MineSkinExceptionShared(Message.ERROR_INVALID_URLSKIN);
                };
            }
            case 403 -> {
                MineSkinErrorResponse apiErrorResponse = response.getBodyAs(MineSkinErrorResponse.class);
                String errorCode2 = apiErrorResponse.getErrorCode();
                String error2 = apiErrorResponse.getError();
                if (errorCode2.equals("invalid_api_key")) {
                    logger.severe("[ERROR] MineSkin API key is not invalid! Reason: " + error2);
                    switch (error2) {
                        case "Invalid API Key" ->
                                logger.severe("The API Key provided is not registered on MineSkin! Please empty \"api.mineSkinKey\" in plugins/SkinsRestorer/config.yml and run /sr reload");
                        case "Client not allowed" ->
                                logger.severe("This server ip is not on the apikey allowed IPs list!");
                        case "Origin not allowed" ->
                                logger.severe("This server Origin is not on the apikey allowed Origins list!");
                        case "Agent not allowed" ->
                                logger.severe("SkinsRestorer's agent \"SkinsRestorer/MineSkinAPI\" is not on the apikey allowed agents list!");
                        default -> logger.severe("Unknown error, please report this to SkinsRestorer's discord!");
                    }

                    throw new MineSkinExceptionShared(Message.ERROR_MS_API_KEY_INVALID);
                }

                throw new MineSkinExceptionShared(Message.ERROR_MS_UNKNOWN);
            }
            case 429 -> {
                MineSkinErrorDelayResponse errorDelayResponse = response.getBodyAs(MineSkinErrorDelayResponse.class);
                // If "Too many requests"
                if (errorDelayResponse.getDelay() != null) {
                    TimeUnit.SECONDS.sleep(errorDelayResponse.getDelay());
                } else if (errorDelayResponse.getNextRequest() != null) {
                    Instant nextRequestInstant = Instant.ofEpochSecond(errorDelayResponse.getNextRequest());
                    int delay = (int) Duration.between(Instant.now(), nextRequestInstant).getSeconds();

                    if (delay > 0) {
                        TimeUnit.SECONDS.sleep(delay);
                    }
                } else { // Should normally not happen
                    TimeUnit.SECONDS.sleep(2);
                }

                return Optional.empty(); // try again after nextRequest
            }
            default -> {
                logger.debug("[ERROR] MineSkin Failed! Unknown error: (Image URL: " + imageUrl + ") " + response.statusCode());
                throw new MineSkinExceptionShared(Message.ERROR_MS_API_FAILED);
            }
        }
    }

    private HttpResponse queryURL(String query) throws IOException {
        for (int i = 0; true; i++) { // try 3 times, if server not responding
            try {
                metricsCounter.increment(MetricsCounter.Service.MINE_SKIN);

                Map<String, String> headers = new HashMap<>();
                Optional<String> apiKey = getApiKey(settings);
                if (apiKey.isPresent()) {
                    headers.put("Authorization", String.format("Bearer %s", apiKey));
                }

                return httpClient.execute(
                        MINESKIN_ENDPOINT,
                        new HttpClient.RequestBody(query, HttpClient.HttpType.FORM),
                        HttpClient.HttpType.JSON,
                        "SkinsRestorer/MineSkinAPI",
                        HttpClient.HttpMethod.POST,
                        headers,
                        90_000
                );
            } catch (IOException e) {
                if (i >= 2) {
                    throw e;
                }
            }
        }
    }

    private Optional<String> getApiKey(SettingsManager settings) {
        String apiKey = settings.getProperty(APIConfig.MINESKIN_API_KEY);
        if (apiKey.isEmpty() || apiKey.equals("key")) {
            return Optional.empty();
        }

        return Optional.of(apiKey);
    }
}
