package ru.lighthouse.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReportAnalytics {
    public static final String NEWLY_BLOCKED = "Новые заблокированные";
    public enum Health { HEALTHY, DEGRADED, OUTAGE }

    public static final class CategorySummary {
        public final String category;
        public final int total;
        public final int available;
        public final int degraded;
        public final int unavailable;
        public final long averagePingMs;
        public final Health health;

        private CategorySummary(String category, Counter counter) {
            this.category = category;
            total = counter.total;
            available = counter.available;
            degraded = counter.degraded;
            unavailable = counter.unavailable;
            averagePingMs = counter.pingCount == 0 ? -1 : counter.pingTotal / counter.pingCount;
            double failedShare = total == 0 ? 0 : (double) unavailable / total;
            health = failedShare >= .5 ? Health.OUTAGE
                : unavailable > 0 || degraded > 0 ? Health.DEGRADED : Health.HEALTHY;
        }

        public String readableStatus() {
            if (health == Health.OUTAGE) return "Серьёзный сбой";
            if (health == Health.DEGRADED) return "Есть проблемы";
            return "Работает нормально";
        }
    }

    private static final class Counter {
        int total, available, degraded, unavailable, pingCount;
        long pingTotal;
    }

    private ReportAnalytics() {}

    public static String categoryIcon(String category) {
        if (category == null) return "•";
        if (NEWLY_BLOCKED.equals(category)) return "!";
        if (category.equals(TargetCatalog.CHATS)) return "Ч";
        if (category.equals(TargetCatalog.BANKING)) return "₽";
        if (category.equals(TargetCatalog.FILES)) return "F";
        if (category.equals(TargetCatalog.GAMES)) return "G";
        if (category.equals(TargetCatalog.AI)) return "AI";
        if (category.equals(TargetCatalog.MEDIA)) return ">";
        if (category.equals(TargetCatalog.SOCIAL)) return "@";
        if (category.equals(TargetCatalog.SHOPPING)) return "S";
        if (category.equals(TargetCatalog.MAIL)) return "@";
        if (category.equals(TargetCatalog.NEWS)) return "N";
        if (category.equals(TargetCatalog.GOVERNMENT)) return "RU";
        if (category.equals(TargetCatalog.SEARCH)) return "?";
        if (category.equals(TargetCatalog.WORK)) return "W";
        if (category.equals(TargetCatalog.MAPS)) return "M";
        if (category.equals(TargetCatalog.TRAVEL)) return "T";
        if (category.equals(TargetCatalog.DEVELOPMENT)) return "</>";
        if (category.equals(TargetCatalog.MONITORING)) return "!";
        if (category.equals(TargetCatalog.DNS)) return "DNS";
        return "•";
    }

    public static String categoryDescription(String category) {
        if (category == null) return "Сервисы этой категории и результаты их сетевой проверки.";
        if (NEWLY_BLOCKED.equals(category)) return "Сервисы, которые были доступны в предыдущем полном скане этой сети, но не ответили сейчас.";
        if (category.equals(TargetCatalog.CHATS)) return "Мессенджеры, веб-чаты и узлы доставки сообщений. Проверка сайта не гарантирует работу звонков и отправки сообщений.";
        if (category.equals(TargetCatalog.BANKING)) return "Банки и платёжная инфраструктура. Проверяется только доступность публичных узлов без входа в аккаунт и операций со средствами.";
        if (category.equals(TargetCatalog.FILES)) return "Облачные хранилища и файлообменники. Проверяется входной узел, а не загрузка личных файлов.";
        if (category.equals(TargetCatalog.GAMES)) return "Игровые магазины, авторизация и публичные серверы. Доступность конкретного игрового матча может отличаться.";
        if (category.equals(TargetCatalog.AI)) return "Публичные сайты нейросетевых сервисов. Подписка, вход и отдельные модели не проверяются.";
        if (category.equals(TargetCatalog.MEDIA)) return "Видео, музыка и потоковые платформы. Для крупных сервисов могут отдельно проверяться сайт и сеть доставки контента.";
        if (category.equals(TargetCatalog.SOCIAL)) return "Социальные сети и публичные веб-интерфейсы.";
        if (category.equals(TargetCatalog.SHOPPING)) return "Магазины, маркетплейсы и сервисы объявлений.";
        if (category.equals(TargetCatalog.MAIL)) return "Публичные веб-интерфейсы почтовых служб без доступа к переписке.";
        if (category.equals(TargetCatalog.NEWS)) return "Новостные и информационные издания.";
        if (category.equals(TargetCatalog.GOVERNMENT)) return "Публичные государственные порталы без авторизации и передачи персональных данных.";
        if (category.equals(TargetCatalog.SEARCH)) return "Поисковые системы, энциклопедии и справочные ресурсы.";
        if (category.equals(TargetCatalog.DEVELOPMENT)) return "Репозитории, облачная инфраструктура и инструменты разработки.";
        if (category.equals(TargetCatalog.WORK)) return "Рабочие платформы, видеосвязь и совместная работа.";
        if (category.equals(TargetCatalog.MAPS)) return "Карты и навигационные веб-сервисы; работа GPS проверяется отдельно.";
        if (category.equals(TargetCatalog.TRAVEL)) return "Транспорт, авиакомпании, билеты и бронирование.";
        if (category.equals(TargetCatalog.MONITORING)) return "Независимые страницы состояния и сервисы наблюдения за доступностью.";
        if (category.equals(TargetCatalog.DNS)) return "Публичные DNS-серверы. Измеряется разрешение тестового домена и время ответа.";
        return "Сервисы этой категории и результаты их сетевой проверки.";
    }

    public static List<CategorySummary> byCategory(List<ProbeResult> results) {
        Map<String, Counter> counters = new LinkedHashMap<>();
        for (String category : categoryOrder()) counters.put(category, new Counter());
        for (ProbeResult result : results) {
            Counter value = counters.computeIfAbsent(result.target.category, ignored -> new Counter());
            value.total++;
            ProbeResult.Status observed = NetworkAssessment.observedStatus(result);
            if (observed == ProbeResult.Status.AVAILABLE) value.available++;
            else if (observed == ProbeResult.Status.DEGRADED) value.degraded++;
            else value.unavailable++;
            if (result.pingMs >= 0) { value.pingTotal += result.pingMs; value.pingCount++; }
        }
        List<CategorySummary> summaries = new ArrayList<>();
        for (Map.Entry<String, Counter> entry : counters.entrySet())
            if (entry.getValue().total > 0) summaries.add(new CategorySummary(entry.getKey(), entry.getValue()));
        return summaries;
    }

    public static List<ProbeResult> newlyBlocked(ScanReport report) {
        List<ProbeResult> values = new ArrayList<>();
        if (report == null || report.newlyUnavailable.isEmpty()) return values;
        for (ProbeResult result : report.results) if (report.newlyUnavailable.contains(result.target.name)) values.add(result);
        return values;
    }

    public static List<String> plainOverview(ScanReport report) {
        List<String> lines = new ArrayList<>();
        switch (report.level) {
            case INCOMPLETE -> lines.add("Недостаточно завершённых замеров. Нельзя сделать вывод о доступности интернета.");
            case NORMAL -> lines.add("Обычный уровень доступности. Часть сервисов может оставаться заблокированной или недоступной.");
            case DEGRADED, SEVERE_RESTRICTIONS -> lines.add("Есть признаки необычных ограничений или массовых сбоев. Сравните результат с обычным уровнем этой сети.");
            case ALLOWLIST_SUSPECTED -> lines.add("Есть признаки белых списков. Ниже перечислены сервисы, успешно ответившие в этой проверке.");
            case NO_CONNECTION -> lines.add("Нет доступа к проверенным интернет-сервисам, включая российские. Причиной может быть не только блокировка.");
        }
        lines.add(report.assessment.explanation);
        if (report.device.vpnDetected) lines.add("Обнаружен VPN: оценка относится к текущему маршруту через VPN, а не обязательно к сети оператора без него.");
        if (!report.complete) lines.add("Скан частичный: проверено " + report.results.size() + " из " + report.expectedTargets
            + " сервисов; не все повторные замеры успели завершиться.");
        int unstableAcrossPasses = 0;
        for (ProbeResult result : report.results) {
            if (result.samples.size() > 1) {
                ProbeResult.Status first = result.samples.get(0).status;
                for (ProbeResult sample : result.samples) if (sample.status != first) { unstableAcrossPasses++; break; }
            }
        }
        if (unstableAcrossPasses > 0) lines.add("Между повторными замерами изменился результат у " + unstableAcrossPasses
            + " сервисов. Это признак нестабильности; подробности каждого замера сохранены в логе.");
        for (NetworkCheckResult check : report.device.observations)
            if (check.id.endsWith("radio_summary") || check.id.endsWith("wifi_summary")) lines.add(check.summary);
        if ("captive_portal".equals(report.device.networkValidation))
            lines.add("Система обнаружила страницу входа в сеть. Откройте её и завершите подключение.");
        else if ("not_validated".equals(report.device.networkValidation))
            lines.add("Android пока не подтвердил полноценный доступ в интернет.");

        List<CategorySummary> affected = new ArrayList<>();
        for (CategorySummary summary : byCategory(report.results))
            if (summary.health != Health.HEALTHY) affected.add(summary);
        affected.sort(Comparator
            .comparingInt((CategorySummary value) -> value.health == Health.OUTAGE ? 0 : 1)
            .thenComparingInt(value -> -value.unavailable));
        if (!affected.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < Math.min(3, affected.size()); i++) {
                CategorySummary value = affected.get(i);
                names.add(value.category + " — " + value.available + " из " + value.total + " доступны");
            }
            lines.add("Больше всего проблем: " + String.join("; ", names) + ".");
        }

        ProbeResult fastestDns = null;
        for (ProbeResult result : report.results)
            if (TargetCatalog.DNS.equals(result.target.category) && NetworkAssessment.observedStatus(result) == ProbeResult.Status.AVAILABLE
                && result.tcpMs >= 0 && (fastestDns == null || result.tcpMs < fastestDns.tcpMs)) fastestDns = result;
        if (fastestDns != null)
            lines.add("Быстрее всех ответил " + fastestDns.target.name + ": DNS-запрос за " + fastestDns.tcpMs + " мс.");
        int dnsFailures = 0, tcpFailures = 0, secureFailures = 0;
        for (ProbeResult result : report.results) {
            if (result.dnsMs < 0) dnsFailures++;
            else if (result.tcpMs < 0) tcpFailures++;
            else if (result.target.probeKind == ServiceTarget.ProbeKind.HTTPS && result.httpsMs < 0) secureFailures++;
        }
        if (dnsFailures > 0) lines.add("Не разрешились доменные имена: " + dnsFailures + ". Возможна проблема DNS.");
        if (tcpFailures > 0) lines.add("После успешного DNS не установилось соединение: " + tcpFailures + ". Возможна фильтрация маршрута или порта.");
        if (secureFailures > 0) lines.add("TCP работает, но защищённое HTTPS-соединение не завершилось: " + secureFailures + ". Возможна проблема TLS/SNI.");
        for (NetworkCheckResult check : report.networkChecks) {
            if (check.status == NetworkCheckResult.Status.FAILED) lines.add(check.name + ": " + check.summary);
            if ("detector404_api".equals(check.id) && "true".equals(check.metrics.get("configured")))
                lines.add("Внешний мониторинг: " + check.summary);
        }
        lines.add("Один неудачный ответ ещё не означает блокировку — важны повторные проверки и сравнение сетей.");
        return lines;
    }

    public static String availableServicesText(ScanReport report) {
        StringBuilder text = new StringBuilder();
        Map<String, List<ProbeResult>> grouped = new LinkedHashMap<>();
        for (ProbeResult result : NetworkAssessment.availableServices(report))
            grouped.computeIfAbsent(result.target.category, ignored -> new ArrayList<>()).add(result);
        for (Map.Entry<String, List<ProbeResult>> entry : grouped.entrySet()) {
            if (text.length() > 0) text.append("\n\n");
            text.append(entry.getKey()).append(":\n");
            for (ProbeResult result : entry.getValue()) text.append("• ").append(result.target.name)
                .append(" — ").append(result.target.host).append(" (HTTP ").append(result.httpCode).append(")\n");
        }
        if (text.length() == 0) return "Успешных ответов веб-сервисов в этом скане нет.";
        return text.toString().trim() + "\n\nПроверены веб-узлы, не отправка сообщений, звонки или передача файлов. Это не гарантирует работу всех функций приложения.";
    }

    private static List<String> categoryOrder() {
        return Arrays.asList(TargetCatalog.CHATS, TargetCatalog.BANKING, TargetCatalog.FILES,
            TargetCatalog.GAMES, TargetCatalog.AI, TargetCatalog.MEDIA, TargetCatalog.SOCIAL,
            TargetCatalog.SHOPPING, TargetCatalog.MAIL, TargetCatalog.NEWS, TargetCatalog.GOVERNMENT,
            TargetCatalog.SEARCH, TargetCatalog.WORK, TargetCatalog.MAPS, TargetCatalog.TRAVEL,
            TargetCatalog.DEVELOPMENT, TargetCatalog.MONITORING, TargetCatalog.DNS);
    }
}
