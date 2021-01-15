package com.huiying.my.hashedWheelTimer;

import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;

import java.util.Date;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class MyHashedWheelTimerTest {

    public static void main(String[] args) {
        Timer timer = new MyHashedWheelTimer();

        Timeout timeout1 = timer.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) {
                System.out.println("timeout1: " + new Date());
            }
        }, 1, TimeUnit.SECONDS);

        Timeout timeout2 = timer.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) {
                System.out.println("timeout2: " + new Date());
            }
        }, 100000, TimeUnit.SECONDS);

        Timeout timeout3 = timer.newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) {
                System.out.println("timeout3: " + new Date());
            }
        }, 100000, TimeUnit.SECONDS);


//        try {
//            Thread.sleep(3000);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
        Set<Timeout> timeouts= timer.stop();
        Iterator<Timeout> it = timeouts.iterator();
        while (it.hasNext()) {
            System.out.println("-------------------------");
            Timeout timeout = (Timeout)it.next();
            System.out.println(timeout.isExpired());
            System.out.println(timeout.task());
            System.out.println(timeout.timer());
            System.out.println(timeout.cancel());
            System.out.println(timeout.isCancelled());
            System.out.println("-------------------------");

        }

    }

}
