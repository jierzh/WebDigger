/**
 * Copyright (c) 2016 21CN.COM . All rights reserved.
 * 
 * Description: web-digger
 * 
 * <pre>
 * Modified log:
 * ------------------------------------------------------
 * Ver.		Date		Author			Description
 * ------------------------------------------------------
 * 1.0		2016年4月11日	linghf		created.
 * </pre>
 */
package org.digger.spider;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.digger.spider.entity.OutputModel;
import org.digger.spider.entity.Request;
import org.digger.spider.entity.Response;
import org.digger.spider.scheduler.QueueScheduler;
import org.digger.spider.scheduler.Scheduler;
import org.digger.spider.tools.FieldResolver;
import org.digger.spider.tools.LinkExtractor;

/**
 * 
 * @class Digger
 * @author linghf
 * @version 1.0
 * @since 2016年4月11日
 */
public class Digger {

    private Scheduler<Request> scheduler = new QueueScheduler();

    private int threadNum = 4;

    private boolean isRunning = false;

    private static ThreadPoolExecutor threadPoolExecutor;

    private static Lock diggerLocker = new ReentrantLock(false);

    private static Condition condition = diggerLocker.newCondition();

    /**
     * 使用静态内部类的方式实现单例模式
     * 
     * @class DiggerBuilder
     * @author linghf
     * @version 1.0
     * @since 2016年7月4日
     */
    private static class DiggerBuilder {
        private static final Digger INSTANCE = new Digger();
    }

    private Digger(){

    }

    public static final Digger getInstance() {
        return DiggerBuilder.INSTANCE;
    }

    /**
     * 设置线程数，默认是4
     * 
     * @param threadNum
     * @return
     */
    public Digger threadNum(int threadNum) {
        this.threadNum = threadNum;
        return this;
    }

    public void addRequests(Spider spider, List<String> urls) {
        diggerLocker.lock();
        try {
            if (urls != null && !urls.isEmpty()) {
                for (String url: urls) {
                    addRequest(spider, url);
                }

                condition.signalAll();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            diggerLocker.unlock();
        }
    }

    /**
	 * 
	 */
    public Digger register(Spider spider) {
        if (spider != null) {
            List<String> startUrls = spider.getStartUrls();

            addRequests(spider, startUrls);
        }
        return this;
    }

    /**
     * 启动线程
     */
    public void start() {
        try {

            if (threadPoolExecutor == null) {
                threadPoolExecutor = new ThreadPoolExecutor(threadNum, threadNum, 3, TimeUnit.SECONDS,
                                new LinkedBlockingQueue<Runnable>());
            }

            if (!this.isRunning) {
                this.isRunning = true;

                new Thread(new Worker()).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        this.isRunning = false;
    }

    /**
     * 爬虫线程
     * 
     * @class Worker
     * @author linghf
     * @version 1.0
     * @since 2016年4月11日
     */
    class Worker implements Runnable {
        public void run() {
            while (isRunning) {
                try {
                    final Request request = scheduler.get();
                    if (request == null) {
                        diggerLocker.lock();

                        try {
                            condition.await();
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            diggerLocker.unlock();
                        }

                    } else {
                        threadPoolExecutor.execute(new Runnable(){

                            public void run() {
                                process(request);
                            }
                        });
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void addRequest(Spider spider, String url) {
        Request request = new Request();
        request.setUrl(url);
        request.setSpider(spider);

        scheduler.put(request);
    }

    public void process(Request request) {
        Spider spider = request.getSpider();

        if (!request.vertify()) {
            return;
        }

        Response response = spider.download(request);
        if (response != null) {

            Class<? extends OutputModel> claz = spider.getOutputModelClass();
            if (claz != null) {
                FieldResolver.resolve(response, claz);
            } else {
                spider.parser(response);
            }
            spider.processItem(response.getItem());

            if (spider.isFollowed()) {
                Set<String> urls = LinkExtractor.extract(response, spider.getFilter());
                if (urls != null && urls.size() > 0) {
                    for (String url: urls) {
                        addRequest(spider, url);
                    }
                }
            }

        }

        try {
            Thread.sleep(1000 * 2);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

}
