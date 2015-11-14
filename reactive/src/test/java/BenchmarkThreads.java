import org.junit.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by player on 13-Nov-15.
 */
public class BenchmarkThreads {


    private static final int TOTAL_PENETRATION = 300;
    private static final int HOW_MANY_THREADS = 300;
    public static final int REQUESTS_PER_SECOND = 100;

    public static final int CPU_INTENSIVE_TASK_COMPLEXITY = 3000;

    public static final int HOW_LONG_IO_TAKES_MS = 200;

    @Test
    public void testThreaded() {
        ThreadedPseudoServer server = new ThreadedPseudoServer();
        penetrate(server);
        server.waitRequestsFinish(TOTAL_PENETRATION);
    }

    @Test
    public void testReactive() {
        ReactivePseudoServer server = new ReactivePseudoServer();
        penetrate(server);
        server.waitRequestsFinish(TOTAL_PENETRATION);
    }

    private void penetrate(PseudoServerI server) {
        double msd = 1d / REQUESTS_PER_SECOND * 1000;

        int ms = (int) msd;
        double nsd = 1d / REQUESTS_PER_SECOND * 1000000000 - (double) ms * 1000000;

        for (int i = 0; i < TOTAL_PENETRATION; i++) {
            server.pseudoCall();
            try {
                Thread.sleep(ms, (int) nsd);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }


    public class ThreadedPseudoServer implements PseudoServerI {

        public AtomicInteger servedRequests = new AtomicInteger(0);

        private ExecutorService executorService = Executors.newFixedThreadPool(HOW_MANY_THREADS);

        @Override
        public void pseudoCall() {


            new Thread() {

                @Override
                public void run() {

                    // some processing
                    new UnitOfWork().run();

                    try {
                        Thread.sleep(HOW_LONG_IO_TAKES_MS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    // more processing
                    new UnitOfWork().run();

                    servedRequests.incrementAndGet();
                }
            }.start();

        }

        @Override
        public void waitRequestsFinish(int requests) {
            while (servedRequests.get() < requests) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            System.out.println(servedRequests);
            executorService.shutdown();
        }

    }

    public static class ReactivePseudoServer implements PseudoServerI {

        private static final int CORES = 4;
        BlockingDeque<Integer> pending = new LinkedBlockingDeque<>();
        BlockingDeque<Long> pendingResponseFromIO = new LinkedBlockingDeque<>();
        public AtomicInteger servedRequests = new AtomicInteger(0);

        List<Thread> threads = new ArrayList<>();

        public void interrupt() {
            for (Thread thread : threads) {
                thread.interrupt();
            }

        }

        public ReactivePseudoServer() {


            Thread ticker = new Thread() {
                @Override
                public void run() {
                    while (true) {


                        while (pendingResponseFromIO.peek() != null
                                && pendingResponseFromIO.peek() < System.currentTimeMillis() - HOW_LONG_IO_TAKES_MS) {
                            pendingResponseFromIO.remove();
                            pending.add(2);
                        }
                        try {
                            Thread.sleep(0, 1000);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            };
            threads.add(ticker);
            ticker.start();


            for (int i = 0; i < CORES; i++) {
                ReactiveServerWorkerThread reactiveServerWorkerThread = new ReactiveServerWorkerThread();
                threads.add(reactiveServerWorkerThread);
                reactiveServerWorkerThread.start();
            }

        }

        @Override
        public void pseudoCall() {
            pending.add(1);
        }

        @Override
        public void waitRequestsFinish(int requests) {
            while (servedRequests.get() < requests) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            interrupt();
            System.out.println(servedRequests);
        }


        public class ReactiveServerWorkerThread extends Thread {
            @Override
            public void run() {

                while (true) {
                    try {
                        // take, work 1st part
                        Integer take = pending.take();

                        if (take == 1) {
                            new UnitOfWork().run();
                            // trigger 'IO' access
                            pendingResponseFromIO.put(System.currentTimeMillis());
                        } else if (take == 2) {
                            new UnitOfWork().run();
                            // finished
                            servedRequests.incrementAndGet();
                        }

                    } catch (InterruptedException e) {
                        return;
                    }
                }

            }
        }

    }

    public static class UnitOfWork implements Runnable {

        public static int ttl = 0;

        @Override
        public void run() {

            String input = UUID.randomUUID().toString();
            MessageDigest instance;
            try {
                instance = MessageDigest.getInstance("SHA-1");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }

            for (int i = 0; i < CPU_INTENSIVE_TASK_COMPLEXITY; i++) {
                instance.update(input.getBytes());
                instance.update(String.valueOf(new Date().getTime()).getBytes());
            }

            ttl += new String(Base64.getEncoder().encode(instance.digest())).length();

        }
    }


}
