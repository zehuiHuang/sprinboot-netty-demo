package com.huiying.threadLocalTest;

/**
 * 1、如何set
 * 在ThreadLocal类中set操作时，是从当前线程Thread中获取一个ThreadLocalMap结构的类，该类是ThreadLocal里的一个内部类。Thread类中维护一个ThreadLocal.ThreadLocalMap  key表示ThreadLocal本身，value为set的值
 * 一个ThreadLocal在一个线程中只能存一个值，多个值必须用多个ThreadLocal，多个ThreadLocal和对应的value组成一个Hash数组表，Hash表存储的规则是：如果key值相同则覆盖value的值，如果key值不同但是hash值相同，在hash值对应的位置需要向后移动一位，同时判定：key值相同就覆盖，不同需要继续移动，重复相同的操作，直到移动的位置没有存数据为止。
 * 2、如何get
 * 从当前线程获取到ThreadLocal.ThreadLocalMap 的属性 通过ThreadLocal本身的key获取一个ThreadLocalMap.Entry ，如果Entry中的value值不为空则直接返回，如果为空，则设置一个初始值为null返回回去。
 */
public class ThreadLocalTest {
    private static final ThreadLocal<String> THREAD_NAME_LOCAL = ThreadLocal.withInitial(() -> Thread.currentThread().getName());
    private static final ThreadLocal<TradeOrder> TRADE_THREAD_LOCAL = new ThreadLocal<>();
    private static final ThreadLocal<TradeOrder> TRADE_THREAD_LOCAL2 = new ThreadLocal<>();
    private static final ThreadLocal<String> local1 = new ThreadLocal<>();
    private static final ThreadLocal<String> local2 = new ThreadLocal<>();

    public static void main(String[] args) {
//        for (int i = 0; i < 2; i++) {
//            int tradeId = i;
//            new Thread(() -> {
//                TradeOrder tradeOrder = new TradeOrder(tradeId, tradeId % 2 == 0 ? "已支付" : "未支付");
//                TRADE_THREAD_LOCAL.set(tradeOrder);
//                System.out.println("threadName: " + THREAD_NAME_LOCAL.get());
//                System.out.println("tradeOrder info：" + TRADE_THREAD_LOCAL.get());
//            }, "thread-" + i).start();
//        }
        TradeOrder tradeOrder = new TradeOrder(1, "已支付");
        local1.set("test1");
        local2.set("test2");
        System.out.println(local1.get());
        System.out.println(local2.get());
    }

    static class TradeOrder {
        long id;
        String status;

        public TradeOrder(int id, String status) {
            this.id = id;
            this.status = status;
        }

        @Override
        public String toString() {
            return "id=" + id + ", status=" + status;
        }
    }
}
