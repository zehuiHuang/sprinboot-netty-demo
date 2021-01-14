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
    //对当前类的字段workerState进行原子性操作
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
            String resourceType = StringUtil.simpleClassName(HashedWheelTimer.class);
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
                long deadline = System.nanoTime() + unit.toNanos(delay) - this.startTime;
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


    @Override
    public Set<Timeout> stop() {
        return null;
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
                long deadline = this.waitForNextTick();
                if (deadline > 0L) {
                    idx = (int) (this.tick & (long) MyHashedWheelTimer.this.mask);
                    this.processCancelledTasks();
                    bucket = MyHashedWheelTimer.this.wheel[idx];
                    this.transferTimeoutsToBuckets();
                    bucket.expireTimeouts(deadline);
                    ++this.tick;
                }
            } while (MyHashedWheelTimer.WORKER_STATE_UPDATER.get(MyHashedWheelTimer.this) == 1);

            MyHashedWheelTimer.HashedWheelBucket[] var5 = MyHashedWheelTimer.this.wheel;
            int var2 = var5.length;

            for (idx = 0; idx < var2; ++idx) {
                bucket = var5[idx];
                bucket.clearTimeouts(this.unprocessedTimeouts);
            }

            while (true) {
                MyHashedWheelTimer.HashedWheelTimeout timeout = (MyHashedWheelTimer.HashedWheelTimeout) MyHashedWheelTimer.this.timeouts.poll();
                if (timeout == null) {
                    this.processCancelledTasks();
                    return;
                }

                if (!timeout.isCancelled()) {
                    this.unprocessedTimeouts.add(timeout);
                }
            }
        }

        private void transferTimeoutsToBuckets() {
            for (int i = 0; i < 100000; ++i) {
                MyHashedWheelTimer.HashedWheelTimeout timeout = (MyHashedWheelTimer.HashedWheelTimeout) MyHashedWheelTimer.this.timeouts.poll();
                if (timeout == null) {
                    break;
                }

                if (timeout.state() != 1) {
                    long calculated = timeout.deadline / MyHashedWheelTimer.this.tickDuration;
                    timeout.remainingRounds = (calculated - this.tick) / (long) MyHashedWheelTimer.this.wheel.length;
                    long ticks = Math.max(calculated, this.tick);
                    int stopIndex = (int) (ticks & (long) MyHashedWheelTimer.this.mask);
                    MyHashedWheelTimer.HashedWheelBucket bucket = MyHashedWheelTimer.this.wheel[stopIndex];
                    bucket.addTimeout(timeout);
                }
            }

        }

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

        private long waitForNextTick() {
            long deadline = MyHashedWheelTimer.this.tickDuration * (this.tick + 1L);

            while (true) {
                long currentTime = System.nanoTime() - MyHashedWheelTimer.this.startTime;
                long sleepTimeMs = (deadline - currentTime + 999999L) / 1000000L;
                if (sleepTimeMs <= 0L) {
                    if (currentTime == -9223372036854775808L) {
                        return -9223372036854775807L;
                    }

                    return currentTime;
                }

                if (PlatformDependent.isWindows()) {
                    sleepTimeMs = sleepTimeMs / 10L * 10L;
                }

                try {
                    Thread.sleep(sleepTimeMs);
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
        private final long deadline;
        private volatile int state = 0;
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

        public void expireTimeouts(long deadline) {
            MyHashedWheelTimer.HashedWheelTimeout next;
            for (MyHashedWheelTimer.HashedWheelTimeout timeout = this.head; timeout != null; timeout = next) {
                next = timeout.next;
                if (timeout.remainingRounds <= 0L) {
                    next = this.remove(timeout);
                    if (timeout.deadline > deadline) {
                        throw new IllegalStateException(String.format("timeout.deadline (%d) > deadline (%d)", timeout.deadline, deadline));
                    }

                    timeout.expire();
                } else if (timeout.isCancelled()) {
                    next = this.remove(timeout);
                } else {
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

        private MyHashedWheelTimer.HashedWheelTimeout pollTimeout() {
            MyHashedWheelTimer.HashedWheelTimeout head = this.head;
            if (head == null) {
                return null;
            } else {
                MyHashedWheelTimer.HashedWheelTimeout next = head.next;
                if (next == null) {
                    this.tail = this.head = null;
                } else {
                    this.head = next;
                    next.prev = null;
                }

                head.next = null;
                head.prev = null;
                head.bucket = null;
                return head;
            }
        }
    }


}
