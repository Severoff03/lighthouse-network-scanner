package ru.lighthouse.core;

import java.util.Arrays;
import java.util.List;

public final class TargetCatalog {
    public static final String CHATS = "Чаты и мессенджеры";
    public static final String BANKING = "Банкинг и платежи";
    public static final String FILES = "Файлообменники и облака";
    public static final String GAMES = "Игровые сервисы";
    public static final String AI = "Нейросети";
    public static final String MEDIA = "Видео и музыка";
    public static final String SOCIAL = "Социальные сети";
    public static final String SHOPPING = "Магазины и объявления";
    public static final String MAIL = "Почта";
    public static final String NEWS = "Новости и медиа";
    public static final String GOVERNMENT = "Государственные сервисы";
    public static final String SEARCH = "Поиск и знания";
    public static final String DEVELOPMENT = "Разработка и инфраструктура";
    public static final String WORK = "Работа и видеосвязь";
    public static final String MAPS = "Карты и навигация";
    public static final String TRAVEL = "Транспорт и путешествия";
    public static final String MONITORING = "Мониторинг доступности";
    public static final String DNS = "DNS-серверы";

    private TargetCatalog() {}

    private static ServiceTarget t(String id, String name, String category, String host, String region) {
        return new ServiceTarget(id, name, category, host, "/", region);
    }

    private static ServiceTarget t(String id, String name, String category, String host, String path, String region) {
        return new ServiceTarget(id, name, category, host, path, region);
    }

    private static ServiceTarget dns(String id, String name, String host) {
        return new ServiceTarget(id, name, DNS, host, "/", "GLOBAL", ServiceTarget.ProbeKind.DNS, 53);
    }

    private static ServiceTarget tcp(String id, String name, String category, String host, String region) {
        return new ServiceTarget(id, name, category, host, "/", region, ServiceTarget.ProbeKind.TCP, 443);
    }

    public static List<ServiceTarget> defaults() {
        return Arrays.asList(
            t("max", "MAX", CHATS, "max.ru", "RU"),
            t("telegram", "Telegram сайт", CHATS, "telegram.org", "GLOBAL"),
            t("telegram_api", "Telegram Bot API", CHATS, "api.telegram.org", "GLOBAL"),
            t("telegram_web", "Telegram Web", CHATS, "web.telegram.org", "GLOBAL"),
            t("telegram_links", "Telegram t.me", CHATS, "t.me", "GLOBAL"),
            tcp("telegram_mtproto_dc2", "Telegram MTProto DC2", CHATS, "149.154.167.51", "GLOBAL"),
            tcp("telegram_mtproto_dc5", "Telegram MTProto DC5", CHATS, "91.108.56.130", "GLOBAL"),
            t("whatsapp", "WhatsApp", CHATS, "www.whatsapp.com", "GLOBAL"),
            t("signal", "Signal", CHATS, "signal.org", "GLOBAL"),
            t("viber", "Viber", CHATS, "www.viber.com", "GLOBAL"),
            t("discord", "Discord", CHATS, "discord.com", "GLOBAL"),
            t("matrix", "Matrix", CHATS, "matrix.org", "GLOBAL"),
            t("element", "Element", CHATS, "element.io", "GLOBAL"),
            t("simplex", "SimpleX Chat", CHATS, "simplex.chat", "GLOBAL"),
            t("threema", "Threema", CHATS, "threema.ch", "GLOBAL"),
            t("session", "Session", CHATS, "getsession.org", "GLOBAL"),
            t("messenger", "Facebook Messenger", CHATS, "www.messenger.com", "GLOBAL"),
            t("imo", "imo", CHATS, "imo.im", "GLOBAL"),
            t("briar", "Briar", CHATS, "briarproject.org", "GLOBAL"),
            t("jami", "Jami", CHATS, "jami.net", "GLOBAL"),
            t("wire", "Wire", CHATS, "wire.com", "GLOBAL"),
            t("deltachat", "Delta Chat", CHATS, "delta.chat", "GLOBAL"),
            t("olvid", "Olvid", CHATS, "olvid.io", "GLOBAL"),
            t("bip", "BiP", CHATS, "bip.com", "GLOBAL"),
            t("zangi", "Zangi", CHATS, "zangi.com", "GLOBAL"),
            t("tamtam", "TamTam", CHATS, "about.tamtam.chat", "RU"),
            t("line", "LINE", CHATS, "line.me", "GLOBAL"),
            t("kakaotalk", "KakaoTalk", CHATS, "www.kakaocorp.com", "GLOBAL"),
            t("wechat", "WeChat", CHATS, "www.wechat.com", "GLOBAL"),
            t("googlechat", "Google Chat", CHATS, "chat.google.com", "GLOBAL"),
            t("rocketchat", "Rocket.Chat", CHATS, "www.rocket.chat", "GLOBAL"),
            t("mattermost", "Mattermost", CHATS, "mattermost.com", "GLOBAL"),
            t("zulip", "Zulip", CHATS, "zulip.com", "GLOBAL"),
            t("tox", "Tox", CHATS, "tox.chat", "GLOBAL"),
            t("status", "Status", CHATS, "status.app", "GLOBAL"),
            t("revolt", "Revolt", CHATS, "revolt.chat", "GLOBAL"),
            t("irccloud", "IRCCloud", CHATS, "www.irccloud.com", "GLOBAL"),
            t("xmpp", "XMPP", CHATS, "xmpp.org", "GLOBAL"),
            t("mumble", "Mumble", CHATS, "www.mumble.info", "GLOBAL"),
            t("google_messages", "Google Messages", CHATS, "messages.google.com", "GLOBAL"),

            t("sber", "Сбер", BANKING, "sberbank.ru", "RU"),
            t("tbank", "Т-Банк", BANKING, "tbank.ru", "RU"),
            t("alfabank", "Альфа-Банк", BANKING, "alfabank.ru", "RU"),
            t("vtb", "ВТБ", BANKING, "vtb.ru", "RU"),
            t("mir", "МИР", BANKING, "mironline.ru", "RU"),
            t("raiffeisen", "Райффайзен Банк", BANKING, "www.raiffeisen.ru", "RU"),
            t("gazprombank", "Газпромбанк", BANKING, "www.gazprombank.ru", "RU"),
            t("yoomoney", "ЮMoney", BANKING, "yoomoney.ru", "RU"),
            t("sovcombank", "Совкомбанк", BANKING, "sovcombank.ru", "RU"),
            t("pochtabank", "Почта Банк", BANKING, "www.pochtabank.ru", "RU"),
            t("psbank", "ПСБ", BANKING, "www.psbank.ru", "RU"),
            t("mtsbank", "МТС Банк", BANKING, "www.mtsbank.ru", "RU"),
            t("rshb", "Россельхозбанк", BANKING, "www.rshb.ru", "RU"),
            t("sbp", "Система быстрых платежей", BANKING, "sbp.nspk.ru", "RU"),

            t("dropbox", "Dropbox", FILES, "www.dropbox.com", "GLOBAL"),
            t("gdrive", "Google Drive", FILES, "drive.google.com", "GLOBAL"),
            t("onedrive", "OneDrive", FILES, "onedrive.live.com", "GLOBAL"),
            t("mega", "MEGA", FILES, "mega.io", "GLOBAL"),
            t("wetransfer", "WeTransfer", FILES, "wetransfer.com", "GLOBAL"),
            t("yadisk", "Яндекс Диск", FILES, "disk.yandex.ru", "RU"),
            t("mailcloud", "Облако Mail.ru", FILES, "cloud.mail.ru", "RU"),
            t("box", "Box", FILES, "www.box.com", "GLOBAL"),
            t("icloud", "iCloud", FILES, "www.icloud.com", "GLOBAL"),
            t("mediafire", "MediaFire", FILES, "www.mediafire.com", "GLOBAL"),
            t("pcloud", "pCloud", FILES, "www.pcloud.com", "GLOBAL"),
            t("sync", "Sync.com", FILES, "www.sync.com", "GLOBAL"),
            t("icedrive", "Icedrive", FILES, "icedrive.net", "GLOBAL"),
            t("filen", "Filen", FILES, "filen.io", "GLOBAL"),

            t("steam", "Steam", GAMES, "store.steampowered.com", "GLOBAL"),
            t("epic", "Epic Games", GAMES, "store.epicgames.com", "GLOBAL"),
            t("xbox", "Xbox", GAMES, "www.xbox.com", "GLOBAL"),
            t("playstation", "PlayStation", GAMES, "www.playstation.com", "GLOBAL"),
            t("battlenet", "Battle.net", GAMES, "battle.net", "GLOBAL"),
            t("ea", "Electronic Arts", GAMES, "www.ea.com", "GLOBAL"),
            t("ubisoft", "Ubisoft", GAMES, "www.ubisoft.com", "GLOBAL"),
            t("riot", "Riot Games", GAMES, "www.riotgames.com", "GLOBAL"),
            t("minecraft", "Minecraft", GAMES, "www.minecraft.net", "GLOBAL"),
            tcp("steam_api", "Steam API", GAMES, "api.steampowered.com", "GLOBAL"),
            tcp("epic_services", "Epic Online Services", GAMES, "account-public-service-prod.ol.epicgames.com", "GLOBAL"),
            tcp("xbox_auth", "Xbox Live авторизация", GAMES, "xsts.auth.xboxlive.com", "GLOBAL"),
            tcp("playstation_auth", "PlayStation Network", GAMES, "my.account.sony.com", "GLOBAL"),
            tcp("battlenet_auth", "Battle.net авторизация", GAMES, "oauth.battle.net", "GLOBAL"),
            tcp("ea_accounts", "EA Accounts", GAMES, "signin.ea.com", "GLOBAL"),
            tcp("ubisoft_services", "Ubisoft Services", GAMES, "public-ubiservices.ubi.com", "GLOBAL"),
            tcp("riot_auth", "Riot авторизация", GAMES, "auth.riotgames.com", "GLOBAL"),
            tcp("minecraft_session", "Minecraft Session", GAMES, "sessionserver.mojang.com", "GLOBAL"),
            tcp("roblox_games", "Roblox Games", GAMES, "games.roblox.com", "GLOBAL"),
            tcp("wot_api", "World of Tanks API", GAMES, "api.worldoftanks.ru", "RU"),
            tcp("gaijin_login", "Gaijin Login", GAMES, "login.gaijin.net", "GLOBAL"),
            tcp("gog_auth", "GOG авторизация", GAMES, "auth.gog.com", "GLOBAL"),
            tcp("nintendo_accounts", "Nintendo Account", GAMES, "accounts.nintendo.com", "GLOBAL"),
            t("vkplay", "VK Play", GAMES, "vkplay.ru", "RU"),
            t("lesta", "Lesta Games", GAMES, "lesta.ru", "RU"),
            t("faceit", "FACEIT", GAMES, "www.faceit.com", "GLOBAL"),
            t("geforcenow", "GeForce NOW", GAMES, "www.nvidia.com", "/geforce-now/", "GLOBAL"),
            t("gog", "GOG", GAMES, "www.gog.com", "GLOBAL"),

            t("openai", "OpenAI", AI, "openai.com", "GLOBAL"),
            t("claude", "Claude", AI, "claude.ai", "GLOBAL"),
            t("gemini", "Google Gemini", AI, "gemini.google.com", "GLOBAL"),
            t("deepseek", "DeepSeek", AI, "chat.deepseek.com", "GLOBAL"),
            t("perplexity", "Perplexity", AI, "www.perplexity.ai", "GLOBAL"),
            t("copilot", "Microsoft Copilot", AI, "copilot.microsoft.com", "GLOBAL"),
            t("mistral", "Mistral AI", AI, "chat.mistral.ai", "GLOBAL"),
            t("huggingface", "Hugging Face", AI, "huggingface.co", "GLOBAL"),
            t("qwen", "Qwen", AI, "chat.qwen.ai", "GLOBAL"),
            t("grok", "Grok", AI, "grok.com", "GLOBAL"),
            t("characterai", "Character.AI", AI, "character.ai", "GLOBAL"),
            t("gigachat", "GigaChat", AI, "giga.chat", "RU"),
            t("fusionbrain", "Kandinsky / FusionBrain", AI, "fusionbrain.ai", "RU"),

            t("rutube", "RuTube", MEDIA, "rutube.ru", "RU"),
            t("youtube", "YouTube — сайт", MEDIA, "www.youtube.com", "/generate_204", "GLOBAL"),
            t("youtube_video", "YouTube — видеосеть", MEDIA, "redirector.googlevideo.com", "/report_mapping", "GLOBAL"),
            t("youtube_images", "YouTube — изображения", MEDIA, "i.ytimg.com", "/generate_204", "GLOBAL"),
            t("tiktok", "TikTok", MEDIA, "www.tiktok.com", "GLOBAL"),
            t("twitch", "Twitch", MEDIA, "www.twitch.tv", "GLOBAL"),
            t("spotify", "Spotify", MEDIA, "www.spotify.com", "GLOBAL"),
            t("vkvideo", "VK Видео", MEDIA, "vkvideo.ru", "RU"),
            t("kinopoisk", "Кинопоиск", MEDIA, "www.kinopoisk.ru", "RU"),
            t("yandexmusic", "Яндекс Музыка", MEDIA, "music.yandex.ru", "RU"),
            t("soundcloud", "SoundCloud", MEDIA, "soundcloud.com", "GLOBAL"),
            t("applemusic", "Apple Music", MEDIA, "music.apple.com", "GLOBAL"),
            t("deezer", "Deezer", MEDIA, "www.deezer.com", "GLOBAL"),
            t("vimeo", "Vimeo", MEDIA, "vimeo.com", "GLOBAL"),
            t("dailymotion", "Dailymotion", MEDIA, "www.dailymotion.com", "GLOBAL"),
            t("netflix", "Netflix", MEDIA, "www.netflix.com", "GLOBAL"),
            t("okko", "Okko", MEDIA, "okko.tv", "RU"),
            t("ivi", "Иви", MEDIA, "www.ivi.ru", "RU"),
            t("wink", "Wink", MEDIA, "wink.ru", "RU"),

            t("vk", "VK", SOCIAL, "vk.com", "RU"),
            t("ok", "Одноклассники", SOCIAL, "ok.ru", "RU"),
            t("x", "X / Twitter", SOCIAL, "x.com", "GLOBAL"),
            t("facebook", "Facebook", SOCIAL, "www.facebook.com", "GLOBAL"),
            t("instagram", "Instagram", SOCIAL, "www.instagram.com", "GLOBAL"),
            t("linkedin", "LinkedIn", SOCIAL, "www.linkedin.com", "GLOBAL"),
            t("reddit", "Reddit", SOCIAL, "www.reddit.com", "GLOBAL"),
            t("pinterest", "Pinterest", SOCIAL, "www.pinterest.com", "GLOBAL"),
            t("bluesky", "Bluesky", SOCIAL, "bsky.app", "GLOBAL"),
            t("threads", "Threads", SOCIAL, "www.threads.net", "GLOBAL"),
            t("mastodon", "Mastodon", SOCIAL, "joinmastodon.org", "GLOBAL"),
            t("tumblr", "Tumblr", SOCIAL, "www.tumblr.com", "GLOBAL"),
            t("livejournal", "LiveJournal", SOCIAL, "www.livejournal.com", "GLOBAL"),
            t("tenchat", "TenChat", SOCIAL, "tenchat.ru", "RU"),

            t("avito", "Avito", SHOPPING, "avito.ru", "RU"),
            t("ozon", "Ozon", SHOPPING, "ozon.ru", "RU"),
            t("wildberries", "Wildberries", SHOPPING, "wildberries.ru", "RU"),
            t("lamoda", "Lamoda", SHOPPING, "www.lamoda.ru", "RU"),
            t("drom", "Дром", SHOPPING, "www.drom.ru", "RU"),
            t("aliexpress", "AliExpress", SHOPPING, "aliexpress.ru", "RU"),
            t("yandexmarket", "Яндекс Маркет", SHOPPING, "market.yandex.ru", "RU"),
            t("megamarket", "Мегамаркет", SHOPPING, "megamarket.ru", "RU"),
            t("dns_shop", "DNS", SHOPPING, "www.dns-shop.ru", "RU"),
            t("citilink", "Ситилинк", SHOPPING, "www.citilink.ru", "RU"),
            t("mvideo", "М.Видео", SHOPPING, "www.mvideo.ru", "RU"),
            t("autoru", "Авто.ру", SHOPPING, "auto.ru", "RU"),

            t("mailru", "Mail.ru", MAIL, "mail.ru", "RU"),
            t("gmail", "Gmail", MAIL, "mail.google.com", "GLOBAL"),
            t("proton", "Proton Mail", MAIL, "proton.me", "GLOBAL"),
            t("outlook", "Outlook", MAIL, "outlook.live.com", "GLOBAL"),
            t("yahoomail", "Yahoo Mail", MAIL, "mail.yahoo.com", "GLOBAL"),
            t("yandexmail", "Яндекс Почта", MAIL, "mail.yandex.ru", "RU"),
            t("zohomail", "Zoho Mail", MAIL, "www.zoho.com", "/mail/", "GLOBAL"),

            t("rbc", "РБК", NEWS, "rbc.ru", "RU"),
            t("ria", "РИА Новости", NEWS, "ria.ru", "RU"),
            t("dzen", "Дзен", NEWS, "dzen.ru", "RU"),
            t("habr", "Хабр", NEWS, "habr.com", "RU"),
            t("pikabu", "Пикабу", NEWS, "pikabu.ru", "RU"),
            t("tass", "ТАСС", NEWS, "tass.ru", "RU"),
            t("lenta", "Лента.ру", NEWS, "lenta.ru", "RU"),
            t("kommersant", "Коммерсантъ", NEWS, "www.kommersant.ru", "RU"),
            t("vedomosti", "Ведомости", NEWS, "www.vedomosti.ru", "RU"),
            t("interfax", "Интерфакс", NEWS, "www.interfax.ru", "RU"),
            t("gosuslugi", "Госуслуги", GOVERNMENT, "gosuslugi.ru", "RU"),
            t("nalog", "ФНС России", GOVERNMENT, "www.nalog.gov.ru", "RU"),
            t("mosru", "Mos.ru", GOVERNMENT, "www.mos.ru", "RU"),
            t("kremlin", "Президент России", GOVERNMENT, "kremlin.ru", "RU"),
            t("government", "Правительство России", GOVERNMENT, "government.ru", "RU"),
            t("zakupki", "ЕИС Закупки", GOVERNMENT, "zakupki.gov.ru", "RU"),
            t("yandex", "Яндекс", SEARCH, "ya.ru", "RU"),
            t("google", "Google", SEARCH, "www.google.com", "GLOBAL"),
            t("wikipedia", "Wikipedia", SEARCH, "www.wikipedia.org", "GLOBAL"),
            t("wikipedia_ru", "Википедия на русском", SEARCH, "ru.wikipedia.org", "GLOBAL"),
            t("bing", "Bing", SEARCH, "www.bing.com", "GLOBAL"),
            t("duckduckgo", "DuckDuckGo", SEARCH, "duckduckgo.com", "GLOBAL"),
            t("bravesearch", "Brave Search", SEARCH, "search.brave.com", "GLOBAL"),
            t("stackoverflow", "Stack Overflow", SEARCH, "stackoverflow.com", "GLOBAL"),

            t("github", "GitHub", DEVELOPMENT, "github.com", "GLOBAL"),
            t("gitlab", "GitLab", DEVELOPMENT, "gitlab.com", "GLOBAL"),
            t("cloudflare", "Cloudflare", DEVELOPMENT, "www.cloudflare.com", "GLOBAL"),
            t("aws", "AWS", DEVELOPMENT, "aws.amazon.com", "GLOBAL"),
            t("azure", "Microsoft Azure", DEVELOPMENT, "azure.microsoft.com", "GLOBAL"),
            t("dockerhub", "Docker Hub", DEVELOPMENT, "hub.docker.com", "GLOBAL"),
            t("npm", "npm", DEVELOPMENT, "www.npmjs.com", "GLOBAL"),
            t("bitbucket", "Bitbucket", DEVELOPMENT, "bitbucket.org", "GLOBAL"),
            t("jetbrains", "JetBrains", DEVELOPMENT, "www.jetbrains.com", "GLOBAL"),
            t("microsoftlearn", "Microsoft Learn", DEVELOPMENT, "learn.microsoft.com", "GLOBAL"),
            t("digitalocean", "DigitalOcean", DEVELOPMENT, "www.digitalocean.com", "GLOBAL"),
            t("vercel", "Vercel", DEVELOPMENT, "vercel.com", "GLOBAL"),
            t("netlify", "Netlify", DEVELOPMENT, "www.netlify.com", "GLOBAL"),

            t("zoom", "Zoom", WORK, "zoom.us", "GLOBAL"),
            t("googlemeet", "Google Meet", WORK, "meet.google.com", "GLOBAL"),
            t("teams", "Microsoft Teams", WORK, "teams.microsoft.com", "GLOBAL"),
            t("slack", "Slack", WORK, "slack.com", "GLOBAL"),
            t("notion", "Notion", WORK, "www.notion.so", "GLOBAL"),
            t("hh", "HeadHunter", WORK, "hh.ru", "RU"),
            t("trello", "Trello", WORK, "trello.com", "GLOBAL"),
            t("asana", "Asana", WORK, "asana.com", "GLOBAL"),
            t("atlassian", "Atlassian", WORK, "www.atlassian.com", "GLOBAL"),
            t("miro", "Miro", WORK, "miro.com", "GLOBAL"),
            t("vkworkspace", "VK WorkSpace", WORK, "biz.mail.ru", "RU"),

            t("2gis", "2ГИС", MAPS, "2gis.ru", "RU"),
            t("googlemaps", "Google Maps", MAPS, "maps.google.com", "GLOBAL"),
            t("openstreetmap", "OpenStreetMap", MAPS, "www.openstreetmap.org", "GLOBAL"),
            t("yandexmaps", "Яндекс Карты", MAPS, "yandex.ru", "/maps/", "RU"),
            t("here", "HERE WeGo", MAPS, "wego.here.com", "GLOBAL"),
            t("mapbox", "Mapbox", MAPS, "www.mapbox.com", "GLOBAL"),
            t("organicmaps", "Organic Maps", MAPS, "organicmaps.app", "GLOBAL"),

            t("rzd", "РЖД", TRAVEL, "www.rzd.ru", "RU"),
            t("aeroflot", "Аэрофлот", TRAVEL, "www.aeroflot.ru", "RU"),
            t("booking", "Booking.com", TRAVEL, "www.booking.com", "GLOBAL"),
            t("tutu", "Туту", TRAVEL, "www.tutu.ru", "RU"),
            t("aviasales", "Авиасейлс", TRAVEL, "www.aviasales.ru", "RU"),
            t("s7", "S7 Airlines", TRAVEL, "www.s7.ru", "RU"),
            t("pobeda", "Победа", TRAVEL, "www.pobeda.aero", "RU"),
            t("utair", "Utair", TRAVEL, "www.utair.ru", "RU"),
            t("ostrovok", "Островок", TRAVEL, "ostrovok.ru", "RU"),

            t("downdetector", "DownDetector", MONITORING, "downdetector.com", "GLOBAL"),
            t("detector404", "Детектор 404", MONITORING, "detector404.ru", "RU"),
            t("ooni", "OONI", MONITORING, "ooni.org", "GLOBAL"),
            t("cloudflare_status", "Cloudflare Status", MONITORING, "www.cloudflarestatus.com", "GLOBAL"),
            t("googlecloud_status", "Google Cloud Status", MONITORING, "status.cloud.google.com", "GLOBAL"),
            t("github_status", "GitHub Status", MONITORING, "www.githubstatus.com", "GLOBAL"),

            dns("cloudflare_dns", "Cloudflare DNS", "1.1.1.1"),
            dns("google_dns", "Google DNS", "8.8.8.8"),
            dns("yandex_dns", "Яндекс DNS", "77.88.8.8"),
            dns("quad9_dns", "Quad9 DNS", "9.9.9.9"),
            dns("adguard_dns", "AdGuard DNS", "94.140.14.14"),
            dns("opendns", "OpenDNS", "208.67.222.222"),
            dns("cleanbrowsing_dns", "CleanBrowsing DNS", "185.228.168.9"),
            dns("comodo_dns", "Comodo Secure DNS", "8.26.56.26")
        );
    }
}
