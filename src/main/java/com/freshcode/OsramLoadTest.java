package com.freshcode;

import org.apache.http.*;
import org.apache.http.client.config.*;
import org.apache.http.client.methods.*;
import org.apache.http.entity.*;
import org.apache.http.impl.client.*;
import org.apache.http.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;


public class OsramLoadTest {


    public static String getProperty(String name, String defaultValue){
        return Optional.ofNullable(System.getenv(name))
                .orElse(Optional.ofNullable(System.getProperty(name))
                                .orElse(defaultValue));
    }

    public static Integer getProperty(String name, Integer defaultValue){
        return Integer.parseInt(getProperty(name, defaultValue.toString()));
    }

    public static Boolean getProperty(String name, Boolean defaultValue){
        return Boolean.parseBoolean(getProperty(name, defaultValue.toString()));
    }


    public static class Statistics {

        private long startTime;
        private AtomicLong minDelay = new AtomicLong(Integer.MAX_VALUE);
        private AtomicLong maxDelay = new AtomicLong(Integer.MIN_VALUE);
        private AtomicLong totalDelay = new AtomicLong(0);
        private AtomicLong totalRequests = new AtomicLong(0);
        private AtomicLong totalErrors = new AtomicLong(0);

        public void incRequests(){
            totalRequests.incrementAndGet();
        }

        public void incErrors(){
            totalErrors.incrementAndGet();
        }

        public void addDelay(long delay){
            minDelay.getAndUpdate(currentDelay -> {
                if(currentDelay > delay){
                    return delay;
                }
                return currentDelay;
            });
            maxDelay.getAndUpdate(currentDelay -> {
                if(currentDelay < delay){
                    return delay;
                }
                return currentDelay;
            });
            totalDelay.addAndGet(delay);
        }

        public void reset(){
            startTime = System.currentTimeMillis();
            totalRequests.set(0);
            totalErrors.set(0);
            totalDelay.set(0);
            minDelay.set(Integer.MAX_VALUE);
            maxDelay.set(Integer.MIN_VALUE);
        }

        public long getStartTime() {
            return startTime;
        }
        public double getErrorsPerS(){
            long totalErrorsCount = totalErrors.get();
            if(totalErrorsCount == 0){
                return 0;
            }
            long timeMs = (System.currentTimeMillis() - startTime);
            return totalErrorsCount / (double) timeMs * 1000;
        }
        public double getRequestsPerS(){
            long totalRequestsCount = totalRequests.get();
            if(totalRequestsCount == 0){
                return 0;
            }
            long timeMs = (System.currentTimeMillis() - startTime);
            return totalRequestsCount / (double) timeMs * 1000;
        }
        public long getMedianDelay(){
            long totalRequestsCount = totalRequests.get();
            if(totalRequestsCount == 0){
                return 0;
            }
            return totalDelay.get() / totalRequestsCount;
        }

        public long getRequests() {
            return totalRequests.get();
        }

        public long getErrors() {
            return totalErrors.get();
        }

        public long getMinDelay() {
            return minDelay.get();
        }

        public long getMaxDelay() {
            return maxDelay.get();
        }
    }

    public void doWork() throws Exception {
        Logger logger = Logger.getLogger("LoadTest");
        LogManager.getLogManager().readConfiguration(this.getClass().getClassLoader().getResourceAsStream("log.properties"));
        String host = getProperty("HOST", "esc-lightify.com");
        int threadsNumber = getProperty("THREADS", 10);
        int requestsPerThread = getProperty("REQUESTS", 0);

        Statistics statistics = new Statistics();
        String url = "https://" + host + "/api/vote";
        logger.info("Starting vote load test on URL " + url);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(5 * 1000)
                .setConnectionRequestTimeout(5 * 1000)
                .build();
        final CloseableHttpClient httpclient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
        List<Thread> threads = new ArrayList<>();
        long start = System.currentTimeMillis();
        Thread monitoring = new Thread(() -> {
            while(true) {
                if(Thread.interrupted()){
                    break;
                }
                if (System.currentTimeMillis() - statistics.getStartTime() > 5 * 1000) {
                    long requests = statistics.getRequests();
                    long errors = statistics.getErrors();
                    logger.info("======================");
                    logger.info("Requests: " + requests);
                    logger.info("Requests/s: " + statistics.getRequestsPerS())
                    ;
                    logger.info("Errors: " + errors);
                    logger.info("Errors/s: " + statistics.getErrorsPerS());
                    logger.info("Errors, %: " + errors / ((double) requests + errors));
                    logger.info("Median delay, ms: " + statistics.getMedianDelay());
                    logger.info("Min delay, ms: " + statistics.getMinDelay());
                    logger.info("Max delay, ms: " + statistics.getMaxDelay());

                    logger.info("======================");
                    statistics.reset();
                }
            }
        });
        monitoring.start();

        for(int i = 0; i < threadsNumber; i++){
            threads.add(new Thread(() -> {
                int requests = 0;
                while(requestsPerThread == 0 || requests++ < requestsPerThread){
                    CloseableHttpResponse response1;
                    long startRequest = System.currentTimeMillis();
                    HttpPost post = new HttpPost(url);
                    try {
                        post.addHeader("x-esc-uuid", UUID.randomUUID().toString());
                        post.addHeader("Content-Type", "application/json");
                        post.setEntity(new StringEntity("{\"points\": 12}"));
                        response1 = httpclient.execute(post);
                    } catch (Exception e) {
                        statistics.incErrors();
                        e.printStackTrace();
                        post.releaseConnection();
                        continue;
                    }
                    try {
                        HttpEntity entity1 = response1.getEntity();
                        EntityUtils.consumeQuietly(entity1);
                        statistics.incRequests();
                        statistics.addDelay(System.currentTimeMillis() - startRequest);
                    } finally {
                        try {
                            post.releaseConnection();
                            response1.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }));
        }

        statistics.reset();
        threads.forEach(Thread::start);
        threads.forEach(thread -> {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        monitoring.interrupt();
        logger.info("submitted " + (threadsNumber * requestsPerThread) + " requests in" + (System.currentTimeMillis() - start));

    }

    public static void main(String[] args) throws Exception {
        OsramLoadTest test = new OsramLoadTest();
        test.doWork();
    }

}
