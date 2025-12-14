import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
public class NetworkTester {

    // ОПТИМАЛЬНЫЕ НАСТРОЙКИ ДЛЯ ИЗБЕЖАНИЯ ПЕРЕПОЛНЕНИЯ БУФЕРОВ
    private static final int MAX_PACKET_SIZE = 1500; // Максимальный размер пакета
    private static final int THREAD_COUNT = 16; // Количесто потоков
    private static final int BURST_SIZE = 500; // Количество пакетов в пачке
    private static final int SEND_BUFFER_SIZE = 16 * 16 * 1024 * 1024; // 268MB Размер буфера

    // АДАПТИВНЫЕ НАСТРОЙКИ
    private static final int RECOVERY_DELAY_MS = 10; // Задержка при ошибках
    private static final int MAX_RETRIES = 3; // Максимум попыток при ошибке

    // СТАТИСТИКА
    private final AtomicLong totalPackets = new AtomicLong(0);
    private final AtomicLong totalBytes = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    private final AtomicLong startTime = new AtomicLong(0);

    private final InetAddress targetAddress;
    private final int targetPort;
    private volatile boolean isRunning = false;

    // АДАПТИВНОЕ УПРАВЛЕНИЕ НАГРУЗКОЙ
    private final AtomicLong lastErrorTime = new AtomicLong(0);
    private final int[] errorCounts = new int[THREAD_COUNT];

    public NetworkTester(String targetHost, int targetPort) throws IOException {
        this.targetAddress = InetAddress.getByName(targetHost);
        this.targetPort = targetPort;

        // ПРОВЕРКА СИСТЕМНЫХ ЛИМИТОВ
        checkSystemLimits();
    }

    //ПРОВЕРКА СИСТЕМНЫХ ЛИМИТОВ
    private void checkSystemLimits() {
        System.out.println("ПРОВЕРКА СИСТЕМНЫХ НАСТРОЕК...");

        // Проверка настройки ядра Linux (если применимо)
        if (System.getProperty("os.name").toLowerCase().contains("linux")) {
            System.out.println("Linux система обнаружена");
            System.out.println("Рекомендуемые настройки для увеличения лимитов:");
            System.out.println("sudo sysctl -w net.core.wmem_max=16777216");
            System.out.println("sudo sysctl -w net.core.rmem_max=16777216");
            System.out.println("sudo sysctl -w net.ipv4.udp_mem='min 102400 102400 204800'");
        }

        System.out.println("Проверка завершена\n");
    }

     //ЗАПУСК СТАБИЛЬНОГО ТЕСТА
    public void runStableTest(int durationSeconds) throws IOException, InterruptedException {
        durationSeconds = Math.min(durationSeconds, 1800); // Макс 30 минут

        System.out.println("\n" + "=".repeat(80));
        System.out.println("ЗАПУСК СТАБИЛЬНОГО ТЕСТА С АДАПТИВНЫМ КОНТРОЛЕМ");
        System.out.println("=".repeat(80));
        System.out.println("Цель: " + targetAddress.getHostAddress() + ":" + targetPort);
        System.out.println("Время: " + durationSeconds + " секунд");
        System.out.println("Потоков: " + THREAD_COUNT);
        System.out.println("Буфер: " + (SEND_BUFFER_SIZE / (1024*1024)) + "MB");
        System.out.println("Режим: Адаптивный (автоматическое восстановление при ошибках)");
        System.out.println("=".repeat(80));

        isRunning = true;
        startTime.set(System.currentTimeMillis());
        long endTime = startTime.get() + (durationSeconds * 1000L);

        // СОЗДАНИЕ ПАКЕТОВ
        List<byte[]> packets = createStablePacketLibrary();

        // ПУЛ ПОТОКОВ С ОГРАНИЧЕННОЙ ОЧЕРЕДЬЮ
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                THREAD_COUNT, THREAD_COUNT,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(THREAD_COUNT * 2),
                new ThreadFactory() {
                    private int counter = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "NetworkWorker-" + counter++);
                        t.setPriority(Thread.NORM_PRIORITY); // Нормальный приоритет для стабильности
                        return t;
                    }
                }
        );

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(THREAD_COUNT);

        // ЗАПУСК ПОТОКОВ
        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    adaptiveWorker(threadId, packets, endTime);
                } catch (Exception e) {
                    System.err.println("[Поток " + threadId + "] Критическая ошибка: " + e.getMessage());
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // ЗАПУСК АДАПТИВНОГО МОНИТОРА
        startAdaptiveMonitor(endTime);

        // СТАРТ
        System.out.println("\nЗАПУСК ТЕСТА...\n");
        startLatch.countDown();

        // ОЖИДАНИЕ
        try {
            boolean completed = finishLatch.await(durationSeconds + 30, TimeUnit.SECONDS);
            if (!completed) {
                System.out.println("\n⚠️ Некоторые потоки не завершились вовремя");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        isRunning = false;
        executor.shutdownNow();

        // РЕЗУЛЬТАТЫ
        printStableResults();
    }

    //АДАПТИВНЫЙ РАБОЧИЙ ПОТОК
    private void adaptiveWorker(int threadId, List<byte[]> packets, long endTime) {
        DatagramSocket socket = null;
        Random rand = new Random(threadId);
        long threadPackets = 0;
        long threadBytes = 0;
        int consecutiveErrors = 0;

        try {
            socket = createOptimizedSocket();

            while (isRunning && System.currentTimeMillis() < endTime) {
                try {
                    // ВЫБОР ПАКЕТА
                    byte[] packet = packets.get(rand.nextInt(packets.size()));
                    DatagramPacket datagram = new DatagramPacket(
                            packet, packet.length, targetAddress, targetPort
                    );

                    // ОТПРАВКА С АДАПТИВНОЙ ЧАСТОТОЙ
                    int sentInBurst = 0;
                    for (int i = 0; i < BURST_SIZE; i++) {
                        socket.send(datagram);
                        sentInBurst++;
                        threadPackets++;
                        threadBytes += packet.length;

                        // ПАУЗА ЕСЛИ БЫЛО МНОГО ОШИБОК
                        if (consecutiveErrors > 0 && i % 10 == 0) {
                            Thread.sleep(1); // 1 мс пауза
                        }
                    }

                    // ОБНОВЛЕНИЕ ГЛОБАЛЬНЫХ СЧЕТЧИКОВ
                    totalPackets.addAndGet(sentInBurst);
                    totalBytes.addAndGet(sentInBurst * packet.length);

                    // СБРОС СЧЕТЧИКА ОШИБОК ПРИ УСПЕХЕ
                    if (consecutiveErrors > 0) {
                        consecutiveErrors = 0;
                        errorCounts[threadId] = 0;
                    }

                    // ПЕРИОДИЧЕСКИЙ ОТЧЕТ
                    if (threadPackets % 50000 == 0) {
                        System.out.printf("[Поток %d] %,d пакетов (ошибок: %d)\n",
                                threadId, threadPackets, errorCounts[threadId]);
                    }

                } catch (IOException e) {
                    // ОБРАБОТКА ОШИБКИ "No buffer space available"
                    errorCounts[threadId]++;
                    totalErrors.incrementAndGet();
                    consecutiveErrors++;
                    lastErrorTime.set(System.currentTimeMillis());

                    System.err.printf("[Поток %d] Ошибка (%d): %s\n",
                            threadId, errorCounts[threadId], e.getMessage());

                    // АДАПТИВНОЕ ВОССТАНОВЛЕНИЕ
                    if (e.getMessage().contains("No buffer space") ||
                            e.getMessage().contains("Resource temporarily unavailable")) {

                        // ЗАКРЫТИЕ И ПЕРЕСОЗДАНИЕ СОКЕТА
                        if (socket != null) {
                            try { socket.close(); } catch (Exception ex) {}
                        }

                        // ПАУЗА ДЛЯ ВОССТАНОВЛЕНИЯ СИСТЕМЫ
                        int recoveryTime = Math.min(100, consecutiveErrors * 50);
                        System.err.printf("[Поток %d] Восстановление: пауза %d мс\n",
                                threadId, recoveryTime);
                        Thread.sleep(recoveryTime);

                        // СОЗДАНИЕ НОВОГО СОКЕТА
                        socket = createOptimizedSocket();

                        // УВЕЛИЧЕНИЕ ПАУЗЫ МЕЖДУ ОТПРАВКАМИ
                        Thread.sleep(5);
                    }

                    // ЕСЛИ СЛИШКОМ МНОГО ОШИБОК - УВЕЛИЧЕНИЕ ПАУЗЫ
                    if (consecutiveErrors > 3) {
                        System.err.printf("[Поток %d] Много ошибок, увеличение паузы до 100мс\n", threadId);
                        Thread.sleep(100);
                    }

                    // ЛИМИТ ОШИБОК
                    if (errorCounts[threadId] > 20) {
                        System.err.printf("[Поток %d] Достигнут лимит ошибок, завершение\n", threadId);
                        break;
                    }
                }

                // КОРОТКАЯ ПАУЗА МЕЖДУ ПАЧКАМИ ДЛЯ СТАБИЛЬНОСТИ
                if (threadPackets % 100000 == 0) {
                    Thread.sleep(5);
                }
            }

        } catch (Exception e) {
            System.err.printf("[Поток %d] Неожиданная ошибка: %s\n", threadId, e.getMessage());
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception e) {}
            }

            System.out.printf("[Поток %d] Завершен: %,d пакетов, %,d ошибок\n",
                    threadId, threadPackets, errorCounts[threadId]);
        }
    }

    //СОЗДАНИЕ ОПТИМИЗИРОВАННОГО СОКЕТА
    private DatagramSocket createOptimizedSocket() throws SocketException {
        DatagramSocket socket = new DatagramSocket();

        // ОПТИМАЛЬНЫЕ НАСТРОЙКИ ДЛЯ СТАБИЛЬНОСТИ
        socket.setSendBufferSize(SEND_BUFFER_SIZE);
        socket.setReceiveBufferSize(SEND_BUFFER_SIZE);
        socket.setReuseAddress(true);
        socket.setTrafficClass(0x10); // Low delay
        socket.setSoTimeout(100); // Таймаут 100мс

        // ДОПОЛНИТЕЛЬНЫЕ НАСТРОЙКИ ДЛЯ LINUX
        try {
            socket.setOption(StandardSocketOptions.SO_SNDBUF, SEND_BUFFER_SIZE);
            socket.setOption(StandardSocketOptions.SO_RCVBUF, SEND_BUFFER_SIZE);
        } catch (Exception e) {
            // Игнорируется если ОС не Linux
        }

        return socket;
    }

    //СОЗДАНИЕ СТАБИЛЬНОЙ БИБЛИОТЕКИ ПАКЕТОВ
    private List<byte[]> createStablePacketLibrary() {
        List<byte[]> packets = new ArrayList<>();
        Random rand = new Random();

        // МЕНЬШЕ РАЗМЕРОВ ДЛЯ СТАБИЛЬНОСТИ
        int[] sizes = {64, 128, 256, 512, 1024, 1450};

        for (int size : sizes) {
            for (int i = 0; i < 3; i++) { // Меньше пакетов каждого размера
                packets.add(createStablePacket(size, rand));
            }
        }

        System.out.println("Создано " + packets.size() + " стабильных пакетов");
        return packets;
    }

    //СОЗДАНИЕ СТАБИЛЬНОГО ПАКЕТА
    private byte[] createStablePacket(int size, Random rand) {
        size = Math.min(size, MAX_PACKET_SIZE);
        byte[] packet = new byte[size];

        // ПРЕДСКАЗУЕМЫЕ ДАННЫЕ (менее ресурсоемко чем случайные)
        for (int i = 0; i < size; i++) {
            packet[i] = (byte) (i & 0xFF);
        }

        // МЕТКА
        byte[] marker = "STABLE".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(marker, 0, packet, 0, Math.min(marker.length, size));

        return packet;
    }

    //АДАПТИВНЫЙ МОНИТОР
    private void startAdaptiveMonitor(long endTime) {
        Thread monitor = new Thread(() -> {
            long lastPackets = 0;
            long lastTime = System.currentTimeMillis();
            long lastAdjustment = System.currentTimeMillis();

            while (isRunning && System.currentTimeMillis() < endTime) {
                try {
                    Thread.sleep(3000); // Мониторим каждые 3 секунды

                    long currentPackets = totalPackets.get();
                    long currentTime = System.currentTimeMillis();
                    long currentErrors = totalErrors.get();

                    if (currentTime > lastTime) {
                        double packetsPerSec = (currentPackets - lastPackets) * 1000.0 / (currentTime - lastTime);
                        double mbps = (totalBytes.get() * 8.0) / ((currentTime - startTime.get()) * 1000.0);

                        // АДАПТИВНЫЙ ВЫВОД
                        String status = "📊";
                        if (currentErrors > lastPackets / 1000) { // Если > 0.1% ошибок
                            status = "⚠️ ";
                        } else if (packetsPerSec > 10000) {
                            status = "⚡";
                        }

                        System.out.printf("\r%s СКОРОСТЬ: %,.0f пак/сек | %.1f Mbps | Ошибок: %,d | Всего: %,d пакетов",
                                status, packetsPerSec, mbps, currentErrors, currentPackets);

                        lastPackets = currentPackets;
                        lastTime = currentTime;
                    }

                    // АВТОМАТИЧЕСКАЯ РЕГУЛИРОВКА ПРИ МНОГИХ ОШИБКАХ
                    if (currentTime - lastAdjustment > 10000) { // Каждые 10 секунд
                        if (currentErrors > 100) {
                            System.out.println("\n⚠️  Много ошибок, рекомендуется уменьшить нагрузку");
                        }
                        lastAdjustment = currentTime;
                    }

                } catch (InterruptedException e) {
                    break;
                }
            }
        });

        monitor.setDaemon(true);
        monitor.start();
    }

    //ВЫВОД РЕЗУЛЬТАТОВ
    private void printStableResults() {
        long duration = System.currentTimeMillis() - startTime.get();
        double seconds = duration / 1000.0;

        long packets = totalPackets.get();
        long bytes = totalBytes.get();
        long errors = totalErrors.get();

        System.out.println("\n\n" + "=".repeat(80));
        System.out.println("📊 РЕЗУЛЬТАТЫ СТАБИЛЬНОГО ТЕСТА");
        System.out.println("=".repeat(80));

        if (seconds > 0) {
            System.out.printf("Время теста: %.1f секунд\n", seconds);
            System.out.printf("Успешных пакетов: %,d\n", packets);
            System.out.printf("Ошибок отправки: %,d\n", errors);
            System.out.printf("Объем данных: %.2f GB\n", bytes / (1024.0 * 1024.0 * 1024.0));
            System.out.printf("Средняя скорость: %,.0f пакетов/сек\n", packets / seconds);
            System.out.printf("Пропускная способность: %.2f Mbps\n",
                    (bytes * 8.0) / (seconds * 1000.0));

            // КАЧЕСТВО СОЕДИНЕНИЯ
            double errorRate = (errors * 100.0) / (packets + errors);
            System.out.printf("Уровень ошибок: %.3f%%\n", errorRate);

            System.out.println("\n🔍 ОЦЕНКА СТАБИЛЬНОСТИ:");
            if (errorRate < 0.1) {
                System.out.println("✅ ОТЛИЧНО: Стабильное соединение, низкий уровень ошибок");
            } else if (errorRate < 1.0) {
                System.out.println("⚠️  ХОРОШО: Умеренный уровень ошибок, сеть под нагрузкой");
            } else if (errorRate < 5.0) {
                System.out.println("⚠️  УДОВЛЕТВОРИТЕЛЬНО: Высокий уровень ошибок, сеть на пределе");
            } else {
                System.out.println("🔴 КРИТИЧЕСКИ: Очень высокий уровень ошибок, оборудование не справляется");
            }
        }

        System.out.println("=".repeat(80));
    }

    //ОСНОВНОЙ МЕТОД
    public static void main(String[] args) {
        System.out.println("NETWORK TESTER");
        System.out.println("Стабильный стресс-тест с адаптивным контролем\n");

        if (args.length < 2) {
            System.out.println("Использование: java NetworkTester <IP> <PORT> [секунд]");
            System.out.println("\nПримеры:");
            System.out.println("  java NetworkTester 192.168.1.1 80 300    # 5 минут теста");
            System.out.println("  java NetworkTester 127.0.0.1 9999 600    # 10 минут теста");
            System.out.println("\n📋 РЕКОМЕНДАЦИИ ПРИ ОШИБКАХ 'No buffer space':");
            System.out.println("  1. Запустите с меньшим количеством потоков");
            System.out.println("  2. Увеличьте системные лимиты");
            System.out.println("  3. Перезапустите программу");
            return;
        }

        try {
            String ip = args[0];
            int port = Integer.parseInt(args[1]);
            int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 300;

            NetworkTester tester = new NetworkTester(ip, port);
            tester.runStableTest(seconds);

        } catch (NumberFormatException e) {
            System.err.println("Ошибка: порт должен быть числом");
        } catch (Exception e) {
            System.err.println("Ошибка: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
