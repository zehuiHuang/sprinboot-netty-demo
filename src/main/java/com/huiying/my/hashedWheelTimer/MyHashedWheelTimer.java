package com.huiying.my.hashedWheelTimer;

import io.netty.util.*;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自己的时间轮处理类
 */
public class MyHashedWheelTimer implements Timer {
    static final InternalLogger logger = InternalLoggerFactory.getInstance(HashedWheelTimer.class);

    //添加的任务就放在该任务队列当中
    private final Queue<MyHashedWheelTimer.HashedWheelTimeout> timeouts;
    //关闭的任务就放到该任务队列当中
    private final Queue<MyHashedWheelTimer.HashedWheelTimeout> cancelledTimeouts;
    //时间轮（一个HashedWheelBucket的数组链表结构）
    private final MyHashedWheelTimer.HashedWheelBucket[] wheel;
    //工作线程启动入口
    private final MyHashedWheelTimer.Worker worker;
    //正常的工作线程
    private final Thread workerThread;

    //
    private static final ResourceLeakDetector<MyHashedWheelTimer> leakDetector;
    //对当前类的字段workerState进行原子性操作：0初始化 1执行中 2已停止
    private static final AtomicIntegerFieldUpdater<MyHashedWheelTimer> WORKER_STATE_UPDATER;


    //已添加的任务数量
    private final AtomicLong pendingTimeouts;
    //最多能添加的任务数量（最大允许等待任务数，HashedWheelTimer 中任务超出该阈值时会抛出异常）
    private final long maxPendingTimeouts;
    //新建时间轮处理器的开始时间毫秒数
    private volatile long startTime;
    //目的是：主线程要等待work线程赋值startTime字段后才能继续执行
    private final CountDownLatch startTimeInitialized;
    //位运算（作用就是获取slot在时间轮数组中的下标位置）,mask=wheel.size()-1,其实就是全部都是1的二进制数
    private final int mask;
    //系统默认设置的指针拨动一次经历最短的时间长度限制
    private static final long MILLISECOND_NANOS;
    //对Work线程的启动做的安全策略（通过）
    private volatile int workerState;
    //指针拨动一次经历的时间长度，单位是纳秒，最小值是1纳秒
    private final long tickDuration;
    //是否开启内存泄漏检测
    private final ResourceLeakTracker<MyHashedWheelTimer> leak;


    //系统中已存在HashedWheelTimer的实例数量，以便对实例对象数量的控制
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
    //系统监控HashedWheelTimer的实例数量，大于64系统会打印日志
    private static final AtomicBoolean WARNED_TOO_MANY_INSTANCES = new AtomicBoolean();


    static {
        MILLISECOND_NANOS = TimeUnit.MILLISECONDS.toNanos(1L);
        leakDetector = ResourceLeakDetectorFactory.instance().newResourceLeakDetector(MyHashedWheelTimer.class, 1);
        WORKER_STATE_UPDATER = AtomicIntegerFieldUpdater.newUpdater(MyHashedWheelTimer.class, "workerState");
    }

    private static void reportTooManyInstances() {
        if (logger.isErrorEnabled()) {
            String resourceType = StringUtil.simpleClassName(MyHashedWheelTimer.class);
            logger.error("You are creating too many " + resourceType + " instances. " + resourceType + " is a shared resource that must be reused across the JVM,so that only a few instances are created.");
        }
    }

    /**
     * 垃圾回收处理时，对类ashedWheelTimer的一些数据处理
     *
     * @throws Throwable
     */
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            //在即将垃圾回收时，对workerState字段进行原子性操作设置成状态2，并且判定至于旧的值不等于2才能减1，即对已经是2状态的不做处理
            if (WORKER_STATE_UPDATER.getAndSet(this, 2) != 2) {
                INSTANCE_COUNTER.decrementAndGet();//
            }
        }
    }


    public MyHashedWheelTimer() {
        this(Executors.defaultThreadFactory());
    }

    public MyHashedWheelTimer(long tickDuration, TimeUnit unit) {
        this(Executors.defaultThreadFactory(), tickDuration, unit);
    }

    public MyHashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(Executors.defaultThreadFactory(), tickDuration, unit, ticksPerWheel);
    }

    public MyHashedWheelTimer(ThreadFactory threadFactory) {
        //时针每次 tick 的时间默认设置100毫秒
        this(threadFactory, 100L, TimeUnit.MILLISECONDS);
    }

    public MyHashedWheelTimer(ThreadFactory threadFactory, long tickDuration, TimeUnit unit) {
        this(threadFactory, tickDuration, unit, 512);
    }

    public MyHashedWheelTimer(ThreadFactory threadFactory, long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, true);
    }

    public MyHashedWheelTimer(ThreadFactory threadFactory, long tickDuration, TimeUnit unit, int ticksPerWheel, boolean leakDetection) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, leakDetection, -1L);
    }

    /**
     * 最细构造函数
     *
     * @param threadFactory
     * @param tickDuration
     * @param unit
     * @param ticksPerWheel
     * @param leakDetection
     * @param maxPendingTimeouts
     */
    public MyHashedWheelTimer(ThreadFactory threadFactory, long tickDuration, TimeUnit unit, int ticksPerWheel, boolean leakDetection, long maxPendingTimeouts) {
        this.worker = new MyHashedWheelTimer.Worker();
        this.startTimeInitialized = new CountDownLatch(1);
        this.timeouts = PlatformDependent.newMpscQueue();
        this.cancelledTimeouts = PlatformDependent.newMpscQueue();
        this.pendingTimeouts = new AtomicLong(0L);
        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        } else if (unit == null) {
            throw new NullPointerException("unit");
        } else if (tickDuration <= 0L) {
            throw new IllegalArgumentException("tickDuration must be greater than 0: " + tickDuration);
        } else if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel);
        } else {
            this.wheel = createWheel(ticksPerWheel);
            this.mask = this.wheel.length - 1;
            long duration = unit.toNanos(tickDuration);
            //如果时间轮的总时间大于等于long类型最大值，则抛出异常
            if (duration >= 9223372036854775807L / (long) this.wheel.length) {
                throw new IllegalArgumentException(String.format("tickDuration: %d (expected: 0 < tickDuration in nanos < %d", tickDuration, 9223372036854775807L / (long) this.wheel.length));
            } else {
                //系统限制：要求duration最小为1纳秒。注：1秒=1000毫秒 1毫秒=1000000纳秒
                if (duration < MILLISECOND_NANOS) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Configured tickDuration %d smaller then %d, using 1ms.", tickDuration, MILLISECOND_NANOS);
                    }

                    this.tickDuration = MILLISECOND_NANOS;
                } else {
                    this.tickDuration = duration;
                }
                this.workerThread = threadFactory.newThread(this.worker);
                //是否开启内存泄漏检测
                this.leak = !leakDetection && this.workerThread.isDaemon() ? null : leakDetector.track(this);
                this.maxPendingTimeouts = maxPendingTimeouts;
                //如果 HashedWheelTimer 的实例数超过 64，会打印错误日志
                if (INSTANCE_COUNTER.incrementAndGet() > 64 && WARNED_TOO_MANY_INSTANCES.compareAndSet(false, true)) {
                    reportTooManyInstances();
                }
            }
        }
    }

    /**
     * 添加任务
     *
     * @param task
     * @param delay
     * @param unit
     * @return
     */
    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        if (task == null) {
            throw new NullPointerException("task");
        } else if (unit == null) {
            throw new NullPointerException("unit");
        } else {
            long pendingTimeoutsCount = this.pendingTimeouts.incrementAndGet();
            if (this.maxPendingTimeouts > 0L && pendingTimeoutsCount > this.maxPendingTimeouts) {
                this.pendingTimeouts.decrementAndGet();
                throw new RejectedExecutionException("Number of pending timeouts (" + pendingTimeoutsCount + ") is greater than or equal to maximum allowed pending timeouts (" + this.maxPendingTimeouts + ")");
            } else {
                this.start();
                long deadline = System.nanoTime() + unit.toNanos(delay) - this.startTime;//执行需要的时间
                if (delay > 0L && deadline < 0L) {
                    deadline = 9223372036854775807L;
                }
                MyHashedWheelTimer.HashedWheelTimeout timeout = new MyHashedWheelTimer.HashedWheelTimeout(this, task, deadline);
                this.timeouts.add(timeout);
                return timeout;
            }
        }
    }


    /**
     * work线程启动入口
     */
    public void start() {
        switch (WORKER_STATE_UPDATER.get(this)) {
            case 0:
                if (WORKER_STATE_UPDATER.compareAndSet(this, 0, 1)) {
                    this.workerThread.start();
                }
            case 1:
                break;
            case 2:
                throw new IllegalStateException("cannot be started once stopped");
            default:
                throw new Error("Invalid WorkerState");
        }
        while (this.startTime == 0L) {
            try {
                System.out.println("startTimeInitialized is await .........");
                this.startTimeInitialized.await();
                System.out.println("startTimeInitialized is await end ......");
            } catch (InterruptedException var2) {
            }
        }
    }


    /**
     * 时间轮定时任务停止
     * @return
     */
    @Override
    public Set<Timeout> stop() {
        //如果不是当前work线程，则不允许停止，即通过其他方式调用该方法不被允许
        if (Thread.currentThread() == this.workerThread) {
            throw new IllegalStateException(HashedWheelTimer.class.getSimpleName() + ".stop() cannot be called from " + TimerTask.class.getSimpleName());
        } else {
            boolean closed;
            //对定时任务组件做关闭的原子性操作
            if (!WORKER_STATE_UPDATER.compareAndSet(this, 1, 2)) {
                if (WORKER_STATE_UPDATER.getAndSet(this, 2) != 2) {
                    INSTANCE_COUNTER.decrementAndGet();
                    if (this.leak != null) {
                        closed = this.leak.close(this);

                        assert closed;
                    }
                }
                return Collections.emptySet();
            } else {
                boolean var7 = false;

                try {
                    var7 = true;
                    closed = false;

                    while(this.workerThread.isAlive()) {
                        this.workerThread.interrupt();

                        try {
                            this.workerThread.join(100L);
                        } catch (InterruptedException var8) {
                            closed = true;
                        }
                    }

                    if (closed) {
                        Thread.currentThread().interrupt();
                        var7 = false;
                    } else {
                        var7 = false;
                    }
                } finally {
                    if (var7) {
                        INSTANCE_COUNTER.decrementAndGet();
                        if (this.leak != null) {
                             closed = this.leak.close(this);
                            assert closed;
                        }
                    }
                }

                INSTANCE_COUNTER.decrementAndGet();
                if (this.leak != null) {
                    closed = this.leak.close(this);

                    assert closed;
                }

                return this.worker.unprocessedTimeouts();
            }
        }
    }


    /**
     * 创建时间轮
     *
     * @param ticksPerWheel
     * @return
     */
    private static MyHashedWheelTimer.HashedWheelBucket[] createWheel(int ticksPerWheel) {
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel);
        } else if (ticksPerWheel > 1073741824) {
            throw new IllegalArgumentException("ticksPerWheel may not be greater than 2^30: " + ticksPerWheel);
        } else {
            ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
            MyHashedWheelTimer.HashedWheelBucket[] wheel = new MyHashedWheelTimer.HashedWheelBucket[ticksPerWheel];
            for (int i = 0; i < wheel.length; ++i) {
                wheel[i] = new MyHashedWheelTimer.HashedWheelBucket();
            }
            return wheel;
        }
    }

    /**
     * 初始化时间轮的slot数量
     *
     * @param ticksPerWheel
     * @return
     */
    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel;
        //取一个不小于 ticksPerWheel 的最小 2 次幂
        for (normalizedTicksPerWheel = 1; normalizedTicksPerWheel < ticksPerWheel; normalizedTicksPerWheel <<= 1) {
        }
        return normalizedTicksPerWheel;
    }

    /**
     * 时间轮工作线程
     */
    private final class Worker implements Runnable {
        private final Set<Timeout> unprocessedTimeouts;
        private long tick;

        private Worker() {
            this.unprocessedTimeouts = new HashSet();
        }

        //任务执行
        public void run() {
            System.out.println("is runing 。。。。。");
            MyHashedWheelTimer.this.startTime = System.nanoTime();
            if (MyHashedWheelTimer.this.startTime == 0L) {
                MyHashedWheelTimer.this.startTime = 1L;
            }
            //目的是在工作线程初始化startTime后，主线程才可以继续向后执行
            //在主线程中有一个await（目的是为了初始化startTime），执行countDown唤醒主线程继续执行
            MyHashedWheelTimer.this.startTimeInitialized.countDown();
            int idx;
            MyHashedWheelTimer.HashedWheelBucket bucket;
            do {
                //计算下次 tick 的时间, 然后sleep 到下次 tick,之后继续向后执行
                long deadline = this.waitForNextTick();
                if (deadline > 0L) {//可能因为溢出或者线程中断，造成 deadline <= 0
                    idx = (int) (this.tick & (long) MyHashedWheelTimer.this.mask);//通过位运算获取当前 tick 在 HashedWheelBucket 数组中对应的下标
                    this.processCancelledTasks();//删除已经关掉的任务
                    bucket = MyHashedWheelTimer.this.wheel[idx];
                    //从队列中取出任务加入对应的 slot 中
                    this.transferTimeoutsToBuckets();
                    //执行到期的任务
                    bucket.expireTimeouts(deadline);
                    ++this.tick;//tick拨动一次，继续执行重复操作
                }
            } while (MyHashedWheelTimer.WORKER_STATE_UPDATER.get(MyHashedWheelTimer.this) == 1);

            //当执行时间轮定时任务停止时，会通过cas修改WORKER_STATE_UPDATER的状态
            System.out.println("WORKER_STATE_UPDATER状态被改变////////////");
            MyHashedWheelTimer.HashedWheelBucket[] var5 = MyHashedWheelTimer.this.wheel;
            int var2 = var5.length;

            for (idx = 0; idx < var2; ++idx) {
                bucket = var5[idx];
                bucket.clearTimeouts(this.unprocessedTimeouts);
            }

            while (true) {
                MyHashedWheelTimer.HashedWheelTimeout timeout = (MyHashedWheelTimer.HashedWheelTimeout) MyHashedWheelTimer.this.timeouts.poll();
                System.out.println("timeout is null:"+timeout==null);
                if (timeout == null) {
                    this.processCancelledTasks();
                    return;
                }

                if (!timeout.isCancelled()) {
                    System.out.println("unprocessedTimeouts.add///////////////////");
                    this.unprocessedTimeouts.add(timeout);
                }
            }
        }

        /**
         * 从队列中取出任务加入各自对应的slot中，最多从队列中取出10万个
         */
        private void transferTimeoutsToBuckets() {
            for (int i = 0; i < 100000; ++i) {
                MyHashedWheelTimer.HashedWheelTimeout timeout = (MyHashedWheelTimer.HashedWheelTimeout) MyHashedWheelTimer.this.timeouts.poll();
                if (timeout == null) {
                    break;
                }
                if (timeout.state() != 1) {
                    //计算任务需要经过多少个 tick（从0开始的）
                    long calculated = timeout.deadline / MyHashedWheelTimer.this.tickDuration;
                    //计算任务需要在时间轮中经历的圈数 remainingRounds
                    timeout.remainingRounds = (calculated - this.tick) / (long) MyHashedWheelTimer.this.wheel.length;//任务执行需要总的tick个数减去时间轮已经走过的tick，在出去时间轮长度，即得到此时此刻该任务在时间轮中的圈数
                    //有一种可能是任务太多，还没来得及从队列中取出来，任务执行的时间已经过期了，那么会把更改任务放到当前tick下对应的双向链表中
                    long ticks = Math.max(calculated, this.tick);
                    //找到该ticks下的slot下对应的双向链表结构的数据
                    int stopIndex = (int) (ticks & (long) MyHashedWheelTimer.this.mask);//时间轮数组中的下标
                    MyHashedWheelTimer.HashedWheelBucket bucket = MyHashedWheelTimer.this.wheel[stopIndex];//取出时间轮 数组下表对应的双向链标结构数据
                    bucket.addTimeout(timeout);//把任务放入时间轮中，等待被执行
                }
            }

        }

        /**
         * 删除已经关掉的任务
         */
        private void processCancelledTasks() {
            while (true) {
                MyHashedWheelTimer.HashedWheelTimeout timeout = (MyHashedWheelTimer.HashedWheelTimeout) MyHashedWheelTimer.this.cancelledTimeouts.poll();
                if (timeout == null) {
                    return;
                }

                try {
                    timeout.remove();
                } catch (Throwable var3) {
                    if (MyHashedWheelTimer.logger.isWarnEnabled()) {
                        MyHashedWheelTimer.logger.warn("An exception was thrown while process a cancellation task", var3);
                    }
                }
            }
        }

        /**
         *
         * 计算下次 tick 的时间, 然后sleep 到下次 tick（解释：tick指针只有在拨动后，再回执行拨动期间经过的slot对应的任务）
         *等待下次tick的时间（从定时启动开始到当前时间的时间长度）
         *
         * @return
         */
        private long waitForNextTick() {
            //tick指针从任务启动到即将tick到下一个slot的总时间
            long deadline = MyHashedWheelTimer.this.tickDuration * (this.tick + 1L);

            while (true) {
                long currentTime = System.nanoTime() - MyHashedWheelTimer.this.startTime;
                long sleepTimeMs = (deadline - currentTime + 999999L) / 1000000L;
                if (sleepTimeMs <= 0L) {//long类型最大值，防止tick没有增加导致随着时间的推移currentTime会变得越来越大，直到大于long类型最大值
                    if (currentTime == -9223372036854775808L) {
                        return -9223372036854775807L;
                    }

                    return currentTime;
                }

                if (PlatformDependent.isWindows()) {
                    sleepTimeMs = sleepTimeMs / 10L * 10L;
                }

                try {
                    Thread.sleep(sleepTimeMs);//睡一个durationtime，
                } catch (InterruptedException var8) {
                    if (MyHashedWheelTimer.WORKER_STATE_UPDATER.get(MyHashedWheelTimer.this) == 2) {
                        return -9223372036854775808L;
                    }
                }
            }
        }

        public Set<Timeout> unprocessedTimeouts() {
            return Collections.unmodifiableSet(this.unprocessedTimeouts);
        }
    }

    /**
     * 时间轮中每个slot中存储的
     */
    private static final class HashedWheelTimeout implements Timeout {
        private static final int ST_INIT = 0;
        private static final int ST_CANCELLED = 1;
        private static final int ST_EXPIRED = 2;
        private static final AtomicIntegerFieldUpdater<MyHashedWheelTimer.HashedWheelTimeout> STATE_UPDATER = AtomicIntegerFieldUpdater.newUpdater(MyHashedWheelTimer.HashedWheelTimeout.class, "state");
        private final MyHashedWheelTimer timer;
        private final TimerTask task;
        //执行需要的时间
        private final long deadline;
        private volatile int state = 0;
        //任务需要在时间轮中经历的圈数
        long remainingRounds;

        MyHashedWheelTimer.HashedWheelTimeout next;
        MyHashedWheelTimer.HashedWheelTimeout prev;
        MyHashedWheelTimer.HashedWheelBucket bucket;

        HashedWheelTimeout(MyHashedWheelTimer timer, TimerTask task, long deadline) {
            this.timer = timer;
            this.task = task;
            this.deadline = deadline;
        }

        void remove() {
            MyHashedWheelTimer.HashedWheelBucket bucket = this.bucket;
            if (bucket != null) {
                bucket.remove(this);
            } else {
                this.timer.pendingTimeouts.decrementAndGet();
            }

        }

        public boolean compareAndSetState(int expected, int state) {
            return STATE_UPDATER.compareAndSet(this, expected, state);
        }

        /**
         * 执行真正的定时任务的逻辑
         */
        public void expire() {
            if (this.compareAndSetState(0, 2)) {
                try {
                    this.task.run(this);
                } catch (Throwable var2) {
                    if (MyHashedWheelTimer.logger.isWarnEnabled()) {
                        MyHashedWheelTimer.logger.warn("An exception was thrown by " + TimerTask.class.getSimpleName() + '.', var2);
                    }
                }
            }
        }

        @Override
        public Timer timer() {
            return null;
        }

        @Override
        public TimerTask task() {
            return null;
        }

        @Override
        public boolean isExpired() {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean cancel() {
            return false;
        }

        public int state() {
            return this.state;
        }

    }


    /**
     * 时间轮中每个slot下对应的双向链表结构的数据
     */
    private static final class HashedWheelBucket {
        //头节点
        private MyHashedWheelTimer.HashedWheelTimeout head;
        //未节点
        private MyHashedWheelTimer.HashedWheelTimeout tail;

        private HashedWheelBucket() {
        }

        public void addTimeout(MyHashedWheelTimer.HashedWheelTimeout timeout) {
            assert timeout.bucket == null;

            timeout.bucket = this;
            if (this.head == null) {
                this.head = this.tail = timeout;
            } else {
                this.tail.next = timeout;
                timeout.prev = this.tail;
                this.tail = timeout;
            }

        }

        /**
         * 执行某slot下HashedWheelBucket中的任务
         * @param deadline
         */
        public void expireTimeouts(long deadline) {
            MyHashedWheelTimer.HashedWheelTimeout next;
            for (MyHashedWheelTimer.HashedWheelTimeout timeout = this.head; timeout != null; timeout = next) {
                next = timeout.next;
                //如果任务需要在时间轮中经历的圈数小于等于0代表 任务应该被执行
                if (timeout.remainingRounds <= 0L) {
                    next = this.remove(timeout);
                    if (timeout.deadline > deadline) {
                        throw new IllegalStateException(String.format("timeout.deadline (%d) > deadline (%d)", timeout.deadline, deadline));
                    }
                    //去执行真正的定时任务的逻辑
                    timeout.expire();
                } else if (timeout.isCancelled()) {
                    next = this.remove(timeout);//如果是已经关闭，则移除该任务
                } else {
                    //否则，圈数减去1，继续向后执行
                    --timeout.remainingRounds;
                }
            }

        }

        public MyHashedWheelTimer.HashedWheelTimeout remove(MyHashedWheelTimer.HashedWheelTimeout timeout) {
            MyHashedWheelTimer.HashedWheelTimeout next = timeout.next;
            if (timeout.prev != null) {
                timeout.prev.next = next;
            }

            if (timeout.next != null) {
                timeout.next.prev = timeout.prev;
            }

            if (timeout == this.head) {
                if (timeout == this.tail) {
                    this.tail = null;
                    this.head = null;
                } else {
                    this.head = next;
                }
            } else if (timeout == this.tail) {
                this.tail = timeout.prev;
            }

            timeout.prev = null;
            timeout.next = null;
            timeout.bucket = null;
            timeout.timer.pendingTimeouts.decrementAndGet();
            return next;
        }

        /**
         *
         * 清除bucket中所有的链表结构数据 并返回所有没执行的任务
         * @param set
         */
        public void clearTimeouts(Set<Timeout> set) {
            while (true) {
                MyHashedWheelTimer.HashedWheelTimeout timeout = this.pollTimeout();
                if (timeout == null) {
                    return;
                }

                if (!timeout.isExpired() && !timeout.isCancelled()) {
                    set.add(timeout);
                }
            }
        }

        /**
         *
         * @return
         */
        private MyHashedWheelTimer.HashedWheelTimeout pollTimeout() {
            MyHashedWheelTimer.HashedWheelTimeout head = this.head;
            if (head == null) {
                return null;
            } else {
                MyHashedWheelTimer.HashedWheelTimeout next = head.next;
                if (next == null) {
                    this.tail = this.head = null;
                } else {
                    //把头节点的next的任务数据设置成头节点
                    this.head = next;
                    //把之前的头头节点设置成null
                    next.prev = null;
                }
                //把
                head.next = null;
                head.prev = null;
                head.bucket = null;
                return head;
            }
        }
    }


}
