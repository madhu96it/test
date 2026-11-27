// import com.sun.net.httpserver.HttpServer;
// import java.io.IOException;
// import java.io.OutputStream;
// import java.net.InetSocketAddress;
// import java.net.URI;
// import java.net.http.HttpClient;
// import java.net.http.HttpRequest;
// import java.net.http.HttpResponse;
// import java.nio.charset.StandardCharsets;
// import java.time.Duration;
// import java.time.Instant;
// import java.time.LocalTime;
// import java.time.format.DateTimeFormatter;
// import java.util.*;
// import java.util.concurrent.*;
// import java.util.concurrent.atomic.AtomicInteger;
// import java.util.concurrent.atomic.AtomicLong;

// public class JMeterListenersRealtimeDemo {

//     record SampleResult(
//             long sampleNumber,
//             Instant startTime,
//             String threadName,
//             String label,
//             long elapsedMs,
//             int responseCode,
//             long bytes,
//             boolean success,
//             String detail
//     ) {}

//     interface SampleListener {
//         void onSample(SampleResult sample);
//         void renderLiveView();
//     }

//     // 1. View Results Tree: Detailed per-sample inspection (best for debugging)
//     static class ViewResultsTreeListener implements SampleListener {
//         private final Deque<SampleResult> recentTree = new ConcurrentLinkedDeque<>();
//         private final int maxNodes = 8;

//         @Override
//         public void onSample(SampleResult s) {
//             recentTree.addLast(s);
//             while (recentTree.size() > maxNodes) {
//                 recentTree.pollFirst();
//             }
//         }

//         @Override
//         public void renderLiveView() {
//             System.out.println("\n--- [1] VIEW RESULTS TREE (Latest " + recentTree.size() + " Samplers) ---");
//             for (SampleResult s : recentTree) {
//                 String icon = s.success() ? "PASS" : "FAIL";
//                 System.out.printf("  [%s] %s | %s (%d ms, HTTP %d, %d B) -> %s%n",
//                         icon, s.label(), s.threadName(), s.elapsedMs(),
//                         s.responseCode(), s.bytes(), s.detail());
//             }
//         }
//     }

//     // 2. Summary Report: Cumulative rolling totals & throughput
//     static class SummaryReportListener implements SampleListener {
//         private final long testStartNanos = System.nanoTime();
//         private final AtomicLong count = new AtomicLong();
//         private final AtomicLong errors = new AtomicLong();
//         private final AtomicLong totalMs = new AtomicLong();
//         private final AtomicLong minMs = new AtomicLong(Long.MAX_VALUE);
//         private final AtomicLong maxMs = new AtomicLong(0);

//         @Override
//         public void onSample(SampleResult s) {
//             count.incrementAndGet();
//             if (!s.success()) errors.incrementAndGet();
//             totalMs.addAndGet(s.elapsedMs());
//             minMs.accumulateAndGet(s.elapsedMs(), Math::min);
//             maxMs.accumulateAndGet(s.elapsedMs(), Math::max);
//         }

//         @Override
//         public void renderLiveView() {
//             long c = count.get();
//             double elapsedSec = Math.max(0.001, (System.nanoTime() - testStartNanos) / 1_000_000_000.0);
//             long avg = c == 0 ? 0 : totalMs.get() / c;
//             long min = c == 0 ? 0 : minMs.get();
//             long max = maxMs.get();
//             double errPct = c == 0 ? 0.0 : (errors.get() * 100.0) / c;
//             double rate = c / elapsedSec;

//             System.out.println("\n--- [2] SUMMARY REPORT ---");
//             System.out.printf("  Samples: %d | Avg: %d ms | Min: %d ms | Max: %d ms | Error%%: %.2f%% | Throughput: %.2f/s%n",
//                     c, avg, min, max, errPct, rate);
//         }
//     }

//     // 3. Aggregate Report: Per-label stats including Median (50%) and 90th Percentile
//     static class AggregateReportListener implements SampleListener {
//         private final long testStartNanos = System.nanoTime();
//         private final Map<String, List<Long>> durationsByLabel = new ConcurrentHashMap<>();
//         private final Map<String, AtomicLong> errorByLabel = new ConcurrentHashMap<>();

//         @Override
//         public void onSample(SampleResult s) {
//             durationsByLabel.computeIfAbsent(s.label(), k -> Collections.synchronizedList(new ArrayList<>()))
//                     .add(s.elapsedMs());
//             errorByLabel.computeIfAbsent(s.label(), k -> new AtomicLong());
//             if (!s.success()) {
//                 errorByLabel.get(s.label()).incrementAndGet();
//             }
//         }

//         @Override
//         public void renderLiveView() {
//             double elapsedSec = Math.max(0.001, (System.nanoTime() - testStartNanos) / 1_000_000_000.0);
//             System.out.println("\n--- [3] AGGREGATE REPORT ---");
//             System.out.printf("  %-22s %7s %7s %7s %7s %7s %8s %9s%n",
//                     "Label", "#Samples", "Average", "Median", "90% Line", "Min", "Max", "Error %");

//             for (var entry : durationsByLabel.entrySet()) {
//                 String label = entry.getKey();
//                 List<Long> copy;
//                 synchronized (entry.getValue()) {
//                     copy = new ArrayList<>(entry.getValue());
//                 }
//                 if (copy.isEmpty()) continue;
//                 Collections.sort(copy);

//                 int n = copy.size();
//                 long sum = 0;
//                 for (long v : copy) sum += v;
//                 long avg = sum / n;
//                 long median = percentile(copy, 50);
//                 long p90 = percentile(copy, 90);
//                 long min = copy.get(0);
//                 long max = copy.get(n - 1);
//                 double errPct = (errorByLabel.get(label).get() * 100.0) / n;

//                 System.out.printf("  %-22s %8d %6d ms %6d ms %6d ms %6d ms %7d ms %7.2f%%%n",
//                         label, n, avg, median, p90, min, max, errPct);
//             }
//             System.out.printf("  Overall Throughput: %.2f samples/sec%n", totalSamples() / elapsedSec);
//         }

//         private long totalSamples() {
//             return durationsByLabel.values().stream().mapToLong(List::size).sum();
//         }

//         private static long percentile(List<Long> sorted, int pct) {
//             int idx = (int) Math.ceil((pct / 100.0) * sorted.size()) - 1;
//             return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
//         }
//     }

//     // 4. View Results in Table: Tabular row log per sample
//     static class ViewResultsInTableListener implements SampleListener {
//         private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
//         private final Deque<SampleResult> rows = new ConcurrentLinkedDeque<>();
//         private final int maxRows = 6;

//         @Override
//         public void onSample(SampleResult s) {
//             rows.addLast(s);
//             while (rows.size() > maxRows) {
//                 rows.pollFirst();
//             }
//         }

//         @Override
//         public void renderLiveView() {
//             System.out.println("\n--- [4] VIEW RESULTS IN TABLE (Latest " + rows.size() + " Rows) ---");
//             System.out.printf("  %-8s %-12s %-14s %-20s %-11s %-7s %-8s%n",
//                     "Sample #", "Start Time", "Thread Name", "Label", "Sample (ms)", "Status", "Bytes");
//             for (SampleResult s : rows) {
//                 String t = LocalTime.ofInstant(s.startTime(), java.time.ZoneId.systemDefault()).format(FMT);
//                 System.out.printf("  %-8d %-12s %-14s %-20s %-11d %-7s %-8d%n",
//                         s.sampleNumber(), t, s.threadName(), s.label(), s.elapsedMs(),
//                         s.success() ? "true" : "false", s.bytes());
//             }
//         }
//     }

//     // 5. Graph Results: Live ASCII time-series of Average & Latest latency + Throughput
//     static class GraphResultsListener implements SampleListener {
//         private final List<Long> allLatencies = Collections.synchronizedList(new ArrayList<>());
//         private final Deque<Long> sparklineHistory = new ArrayDeque<>();
//         private final int width = 32;

//         @Override
//         public void onSample(SampleResult s) {
//             allLatencies.add(s.elapsedMs());
//             synchronized (sparklineHistory) {
//                 sparklineHistory.addLast(s.elapsedMs());
//                 if (sparklineHistory.size() > width) {
//                     sparklineHistory.pollFirst();
//                 }
//             }
//         }

//         @Override
//         public void renderLiveView() {
//             List<Long> snap;
//             synchronized (allLatencies) {
//                 snap = new ArrayList<>(allLatencies);
//             }
//             if (snap.isEmpty()) return;

//             double avg = snap.stream().mapToLong(Long::longValue).average().orElse(0);
//             List<Long> sorted = new ArrayList<>(snap);
//             Collections.sort(sorted);
//             double median = sorted.get(sorted.size() / 2);

//             List<Long> spark;
//             synchronized (sparklineHistory) {
//                 spark = new ArrayList<>(sparklineHistory);
//             }
//             long maxSpark = Math.max(1, spark.stream().mapToLong(Long::longValue).max().orElse(1));
//             String bars = " .:-=+*#%@";
//             StringBuilder chart = new StringBuilder();
//             for (long val : spark) {
//                 int idx = (int) Math.min(bars.length() - 1, Math.round((val * 1.0 / maxSpark) * (bars.length() - 1)));
//                 chart.append(bars.charAt(idx));
//             }

//             System.out.println("\n--- [5] GRAPH RESULTS (Live Latency Trend) ---");
//             System.out.printf("  Avg: %.1f ms | Median: %.1f ms | Peak Window: %d ms%n", avg, median, maxSpark);
//             System.out.println("  Trend [" + chart + "]");
//         }
//     }

//     public static void main(String[] args) throws Exception {
//         // Start a local mock API server on port 8089
//         HttpServer server = HttpServer.create(new InetSocketAddress(8089), 0);
//         AtomicInteger reqCounter = new AtomicInteger();

//         server.createContext("/api/orders", exchange -> {
//             int n = reqCounter.incrementAndGet();
//             try {
//                 // Simulate variable latency and occasional 503 error
//                 if (n % 7 == 0) {
//                     Thread.sleep(45);
//                     byte[] err = "{\"error\":\"Service Unavailable\"}".getBytes(StandardCharsets.UTF_8);
//                     exchange.sendResponseHeaders(503, err.length);
//                     try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
//                 } else {
//                     int delay = (n % 5 == 0) ? 140 : 25 + (n * 7) % 45;
//                     Thread.sleep(delay);
//                     byte[] ok = "{\"orderId\":1042,\"status\":\"CONFIRMED\"}".getBytes(StandardCharsets.UTF_8);
//                     exchange.sendResponseHeaders(200, ok.length);
//                     try (OutputStream os = exchange.getResponseBody()) { os.write(ok); }
//                 }
//             } catch (InterruptedException ignored) {
//                 Thread.currentThread().interrupt();
//             }
//         });
//         server.start();

//         // Register all 5 listeners
//         List<SampleListener> listeners = List.of(
//                 new ViewResultsTreeListener(),
//                 new SummaryReportListener(),
//                 new AggregateReportListener(),
//                 new ViewResultsInTableListener(),
//                 new GraphResultsListener()
//         );

//         HttpClient client = HttpClient.newBuilder()
//                 .connectTimeout(Duration.ofSeconds(2))
//                 .build();

//         AtomicInteger sampleSeq = new AtomicInteger();
//         String[] endpoints = {"GET /api/orders", "POST /api/orders", "GET /api/orders/1042"};

//         // Live dashboard refresher every 600 ms while virtual users run
//         ScheduledExecutorService uiScheduler = Executors.newSingleThreadScheduledExecutor();
//         uiScheduler.scheduleAtFixedRate(() -> {
//             System.out.print("\033[H\033[2J");
//             System.out.flush();
//             System.out.println("=== REAL-TIME JMETER-STYLE LISTENER STREAM (JAVA) ===");
//             for (SampleListener listener : listeners) {
//                 listener.renderLiveView();
//             }
//         }, 400, 600, TimeUnit.MILLISECONDS);

//         // Simulate 4 worker threads firing 24 total samples
//         int threads = 4;
//         int samplesPerThread = 6;
//         ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
//             Thread t = new Thread(r);
//             t.setName("Thread Group 1-" + t.getId());
//             return t;
//         });

//         List<Callable<Void>> tasks = new ArrayList<>();
//         for (int t = 0; t < threads; t++) {
//             final int threadIdx = t;
//             tasks.add(() -> {
//                 for (int i = 0; i < samplesPerThread; i++) {
//                     String label = endpoints[(threadIdx + i) % endpoints.length];
//                     long sampleNum = sampleSeq.incrementAndGet();
//                     Instant start = Instant.now();
//                     long t0 = System.nanoTime();

//                     HttpRequest request = HttpRequest.newBuilder()
//                             .uri(URI.create("http://localhost:8089/api/orders"))
//                             .timeout(Duration.ofSeconds(2))
//                             .GET()
//                             .build();

//                     HttpResponse<byte[]> resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
//                     long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
//                     int code = resp.statusCode();
//                     boolean ok = code >= 200 && code < 400;
//                     long bytes = resp.body().length;
//                     String detail = ok ? "OK payload verified" : "HTTP " + code + " response";

//                     SampleResult result = new SampleResult(
//                             sampleNum, start, Thread.currentThread().getName(),
//                             label, elapsedMs, code, bytes, ok, detail
//                     );
//                     for (SampleListener l : listeners) {
//                         l.onSample(result);
//                     }
//                     Thread.sleep(90);
//                 }
//                 return null;
//             });
//         }

//         pool.invokeAll(tasks);
//         pool.shutdown();
//         pool.awaitTermination(5, TimeUnit.SECONDS);

//         // One final render after all requests complete
//         Thread.sleep(700);
//         uiScheduler.shutdown();
//         server.stop(0);

//         System.out.println("\n=== FINAL SNAPSHOT ===");
//         for (SampleListener listener : listeners) {
//             listener.renderLiveView();
//         }
//     }
// }
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
public class JMeterListenersRealtimeDemo {
  record SampleResult(
            long sampleNumber,
            Instant startTime,
            String threadName,
            String label,
            long elapsedMs,
            int responseCode,
            long bytes,
            boolean success,
            String detail
    ) {}
interface SampleListener {
        void onSample(SampleResult sample);
        void renderLiveView();
    }
 // 1. View Results Tree
    static class ViewResultsTreeListener implements SampleListener {
        private final Deque<SampleResult> recentTree = new ConcurrentLinkedDeque<>();
        private final int maxNodes = 8;
 @Override
        public void onSample(SampleResult s) {
            recentTree.addLast(s);
            while (recentTree.size() > maxNodes) {
                recentTree.pollFirst();
            } }
 @Override
        public void renderLiveView() {
            System.out.println("\n--- [1] VIEW RESULTS TREE ---");
            for (SampleResult s : recentTree) {
                String icon = s.success() ? "PASS" : "FAIL";
 System.out.printf(
                        " [%s] %s | %s (%d ms, HTTP %d, %d B) -> %s%n",
                        icon,
                        s.label(),
                        s.threadName(),
                        s.elapsedMs(),
                        s.responseCode(),
                        s.bytes(),
                        s.detail());
            }
        }
    }
 // 2. Summary Report
    static class SummaryReportListener implements SampleListener {
 private final long startNanos = System.nanoTime();
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong errors = new AtomicLong();
        private final AtomicLong totalMs = new AtomicLong();
        private final AtomicLong minMs = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxMs = new AtomicLong();
        @Override
        public void onSample(SampleResult s) {
            count.incrementAndGet();
 if (!s.success()) {
                errors.incrementAndGet();
            }
 totalMs.addAndGet(s.elapsedMs());
            minMs.accumulateAndGet(s.elapsedMs(), Math::min);
            maxMs.accumulateAndGet(s.elapsedMs(), Math::max);
        }
        @Override
        public void renderLiveView() {
 long c = count.get();
 double elapsedSeconds =
                    Math.max(0.001,
                            (System.nanoTime() - startNanos) / 1_000_000_000.0);
 long avg = c == 0 ? 0 : totalMs.get() / c;
System.out.println("\n--- [2] SUMMARY REPORT ---");
 System.out.printf(
                    " Samples=%d | Avg=%d ms | Min=%d ms | Max=%d ms | Error%%=%.2f | Throughput=%.2f/sec%n",
                    c,
                    avg,
                    c == 0 ? 0 : minMs.get(),
                    maxMs.get(),
                    c == 0 ? 0 : errors.get() * 100.0 / c,
                    c / elapsedSeconds);
        }
    }
 // 3. Aggregate Report
    static class AggregateReportListener implements SampleListener {
 private final Map<String, List<Long>> durations = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> errors = new ConcurrentHashMap<>();
        private final long startNanos = System.nanoTime();
  @Override
        public void onSample(SampleResult s) {
 durations.computeIfAbsent(
                    s.label(),
                    k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(s.elapsedMs());
 errors.computeIfAbsent(
                    s.label(),
                    k -> new AtomicLong());
 if (!s.success()) {
                errors.get(s.label()).incrementAndGet();
            }
        }
  @Override
        public void renderLiveView() {
  System.out.println("\n--- [3] AGGREGATE REPORT ---");
  System.out.printf(
                    "%-20s %-8s %-8s %-8s %-8s %-8s %-8s%n",
                    "Label",
                    "Samples",
                    "Avg",
                    "Median",
                    "P90",
                    "Max",
                    "Err%");

            for (var e : durations.entrySet()) {

                List<Long> copy;

                synchronized (e.getValue()) {
                    copy = new ArrayList<>(e.getValue());
                }

                if (copy.isEmpty()) {
                    continue;
                }

                Collections.sort(copy);

                int n = copy.size();

                long sum = copy.stream().mapToLong(Long::longValue).sum();

                long avg = sum / n;
                long median = percentile(copy, 50);
                long p90 = percentile(copy, 90);
                long max = copy.get(n - 1);

                double errPct =
                        errors.get(e.getKey()).get() * 100.0 / n;

                System.out.printf(
                        "%-20s %-8d %-8d %-8d %-8d %-8d %-8.2f%n",
                        e.getKey(),
                        n,
                        avg,
                        median,
                        p90,
                        max,
                        errPct);
            }

            double throughput =
                    totalSamples()
                            / Math.max(
                            0.001,
                            (System.nanoTime() - startNanos) / 1_000_000_000.0);

            System.out.printf(" Overall Throughput = %.2f/sec%n", throughput);
        }

        private long totalSamples() {
            return durations.values()
                    .stream()
                    .mapToLong(List::size)
                    .sum();
        }

        private long percentile(List<Long> sorted, int p) {
            int index =
                    (int) Math.ceil(sorted.size() * p / 100.0) - 1;

            return sorted.get(
                    Math.max(
                            0,
                            Math.min(index, sorted.size() - 1)));
        }
    }

    // 4. Results Table
    static class ViewResultsInTableListener implements SampleListener {

        private final Deque<SampleResult> rows =
                new ConcurrentLinkedDeque<>();

        private static final DateTimeFormatter FMT =
                DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

        @Override
        public void onSample(SampleResult s) {
            rows.addLast(s);

            while (rows.size() > 6) {
                rows.pollFirst();
            }
        }

        @Override
        public void renderLiveView() {

            System.out.println("\n--- [4] RESULTS TABLE ---");

            System.out.printf(
                    "%-6s %-12s %-15s %-20s %-8s %-8s%n",
                    "#",
                    "Time",
                    "Thread",
                    "Label",
                    "Code",
                    "ms");

            for (SampleResult s : rows) {

                String time =
                        LocalTime.ofInstant(
                                        s.startTime(),
                                        java.time.ZoneId.systemDefault())
                                .format(FMT);

                System.out.printf(
                        "%-6d %-12s %-15s %-20s %-8d %-8d%n",
                        s.sampleNumber(),
                        time,
                        s.threadName(),
                        s.label(),
                        s.responseCode(),
                        s.elapsedMs());
            }
        }
    }

    // 5. Graph Results
    static class GraphResultsListener implements SampleListener {

        private final List<Long> latencies =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onSample(SampleResult s) {
            latencies.add(s.elapsedMs());
        }

        @Override
        public void renderLiveView() {

            if (latencies.isEmpty()) {
                return;
            }

            double avg =
                    latencies.stream()
                            .mapToLong(Long::longValue)
                            .average()
                            .orElse(0);

            System.out.println("\n--- [5] GRAPH RESULTS ---");

            System.out.printf(
                    " Samples=%d | Avg Latency=%.2f ms%n",
                    latencies.size(),
                    avg);
        }
    }

    public static void main(String[] args) throws Exception {

        List<SampleListener> listeners = List.of(
                new ViewResultsTreeListener(),
                new SummaryReportListener(),
                new AggregateReportListener(),
                new ViewResultsInTableListener(),
                new GraphResultsListener()
        );

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        String[] labels = {
                "GET /posts/1",
                "GET /posts/2",
                "GET /posts/3"
        };

        String[] urls = {
                "https://jsonplaceholder.typicode.com/posts/1",
                "https://jsonplaceholder.typicode.com/posts/2",
                "https://jsonplaceholder.typicode.com/posts/3"
        };

        ScheduledExecutorService dashboard =
                Executors.newSingleThreadScheduledExecutor();

        dashboard.scheduleAtFixedRate(() -> {

            System.out.print("\033[H\033[2J");
            System.out.flush();

            System.out.println(
                    "=== REAL TIME JMETER LISTENER DASHBOARD ===");

            listeners.forEach(SampleListener::renderLiveView);

        }, 1, 1, TimeUnit.SECONDS);

        int threads = 4;
        int samplesPerThread = 6;

        ExecutorService pool =
                Executors.newFixedThreadPool(threads);

        AtomicInteger counter = new AtomicInteger();

        List<Callable<Void>> jobs = new ArrayList<>();

        for (int t = 0; t < threads; t++) {

            final int worker = t;

            jobs.add(() -> {

                for (int i = 0; i < samplesPerThread; i++) {

                    int index =
                            (worker + i) % urls.length;

                    String label = labels[index];
                    String url = urls[index];

                    long sampleNo =
                            counter.incrementAndGet();

                    Instant start = Instant.now();

                    long begin = System.nanoTime();

                    HttpRequest request =
                            HttpRequest.newBuilder()
                                    .uri(URI.create(url))
                                    .timeout(Duration.ofSeconds(10))
                                    .header("Accept", "application/json")
                                    .GET()
                                    .build();

                    HttpResponse<byte[]> response =
                            client.send(
                                    request,
                                    HttpResponse.BodyHandlers.ofByteArray());

                    long elapsedMs =
                            (System.nanoTime() - begin)
                                    / 1_000_000;

                    SampleResult result =
                            new SampleResult(
                                    sampleNo,
                                    start,
                                    Thread.currentThread().getName(),
                                    label,
                                    elapsedMs,
                                    response.statusCode(),
                                    response.body().length,
                                    response.statusCode() == 200,
                                    "JSONPlaceholder API");

                    listeners.forEach(
                            l -> l.onSample(result));

                    Thread.sleep(100);
                }
                return null;
            });
        }

        pool.invokeAll(jobs);

        pool.shutdown();

        pool.awaitTermination(
                30,
                TimeUnit.SECONDS);

        Thread.sleep(1500);

        dashboard.shutdownNow();

        System.out.println("\n=== FINAL REPORT ===");

        listeners.forEach(
                SampleListener::renderLiveView);
    }
}